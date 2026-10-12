#!/usr/bin/env groovy

def call() {
    // Force the execution framework to grab your high-spec hardware first
    node('remote-node-4-ci') {
        
        // Define global environment controls manually within the node context
        def REGISTRY_CRED      = 'internal-artifactory-auth'
        def MAX_ALLOWED_CPUS   = 1
        def MAX_ALLOWED_MEMORY = 2 // Gigabytes
        def DEFAULT_CPUS       = '1'
        def DEFAULT_MEMORY     = '1g'
        def DEFAULT_BUILD_CMD  = 'echo "No build command provided by developer"'
        
        // Target paths and parameters populated dynamically
        def TEAM_FOLDER        = ""
        def TARGET_IMAGE       = ""
        def BUILD_CMD          = ""
        def RUN_SECURITY_SCANS = "false"
        def FINAL_CPUS         = ""
        def FINAL_MEM          = ""
        def FINAL_SWAP         = ""

        stage('Ingest & Validate Configuration') {
            // Read YAML directly from the checkout workspace on the JUMP HOST
            if (!fileExists('pipeline.yaml')) {
                error "Pipeline Aborted: Missing required 'pipeline.yaml' file in repository root!"
            }
            
            def config = readYaml file: 'pipeline.yaml'
            
            TEAM_FOLDER = config.pipeline.team_folder ?: error("Pipeline Aborted: 'team_folder' is required in pipeline.yaml!")
            
            if (!config.pipeline.container?.image) {
                error "Pipeline Aborted: 'container.image' is required! Explicitly specify a container."
            }
            TARGET_IMAGE = config.pipeline.container.image
            BUILD_CMD    = config.pipeline.environment?.BUILD_COMMAND ?: DEFAULT_BUILD_CMD

            // Multi-language artifact parsing check
            def lowerCmd = BUILD_CMD.toLowerCase()
            if (lowerCmd.contains("package") || lowerCmd.contains("install") || lowerCmd.contains("deploy") || 
                lowerCmd.contains("build") || lowerCmd.contains("setup.py bdist") || lowerCmd.contains("cargo build")) {
                echo "📦 Artifact generation detected. Enforcing security scanners (Trivy & SonarQube)..."
                RUN_SECURITY_SCANS = "true"
            } else {
                echo "ℹ️ Baseline verification detected. Skipping scanners..."
                RUN_SECURITY_SCANS = "false"
            }

            // Resource Limit Constraints Verification
            def requestedCpus = (config.pipeline.container?.cpus ?: DEFAULT_CPUS).toInteger()
            def requestedMem  = (config.pipeline.container?.memory ?: DEFAULT_MEMORY).replaceAll(/[gG]/, "").toInteger()
            
            if (requestedCpus > MAX_ALLOWED_CPUS) {
                error "Pipeline Aborted: Requested CPUs (${requestedCpus}) exceeds policy limit of ${MAX_ALLOWED_CPUS}!"
            }
            FINAL_CPUS = requestedCpus

            if (requestedMem > MAX_ALLOWED_MEMORY) {
                error "Pipeline Aborted: Requested Memory (${requestedMem}g) exceeds policy limit of ${MAX_ALLOWED_MEMORY}g!"
            }
            FINAL_MEM = "${requestedMem}g"
            
            def rawSwap = config.pipeline.container?.memory_swap ?: FINAL_MEM
            FINAL_SWAP = rawSwap.contains("g") ? rawSwap : "${rawSwap}g"
        }

        stage('Execute Multitenant Secure Workload') {
            // Bind tool configuration to execution node context
            def sonarScannerHome = tool 'SonarQubeScanner'
            
            withDockerRegistry([credentialsId: "${REGISTRY_CRED}"]) {
                def dynamicArgs = "--cpus='${FINAL_CPUS}' --memory='${FINAL_MEM}' --memory-swap='${FINAL_SWAP}' -v maven-repo:/root/.m2"
                
                echo "Spinning up image locally on Jump Host hardware: ${TARGET_IMAGE}"
                
                // Launches the container locally on your 512GB server partition
                docker.image("${TARGET_IMAGE}").inside(dynamicArgs) {
                    
                    if (RUN_SECURITY_SCANS == "true") {
                        stage('Trivy Image Scan') {
                            sh "trivy image --severity HIGH,CRITICAL --exit-code 1 ${TARGET_IMAGE}"
                        }
                    }

                    stage('Compile & Test Execution') {
                        sh "${BUILD_CMD}"
                    }

                    if (RUN_SECURITY_SCANS == "true") {
                        stage('SonarQube Static Analysis') {
                            withSonarQubeEnv('MySonarQubeServer') {
                                sh "${sonarScannerHome}/bin/sonar-scanner -Dsonar.projectKey=${TEAM_FOLDER} -Dsonar.sources=."
                            }
                        }
                    }

                    // Dynamic inclusion execution pattern
                    try {
                        echo "🚀 Routing post-actions to library module: src/${TEAM_FOLDER}/CustomLogic.groovy"
                        def teamClass = this.class.classLoader.loadClass("${TEAM_FOLDER}.CustomLogic").newInstance(this)
                        teamClass.execute()
                    } catch (ClassNotFoundException e) {
                        echo "No custom class logic file discovered for ${TEAM_FOLDER}. Skipping custom steps."
                    }
                }
            }
        }

        stage('Publish Results') {
            // Ensure JUnit results are archived on the node context safely
            junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml, **/test-results/**/*.xml, **/junit.xml, **/build/test-results/**/*.xml'
        }
    }
}

