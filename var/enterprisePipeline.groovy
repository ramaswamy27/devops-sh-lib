#!/usr/bin/env groovy

def call() {
    pipeline {
        agent { label 'remote-node-4-ci' }
        
        environment {
            REGISTRY_CRED      = 'internal-artifactory-auth'
            
            // GLOBAL CORPORATE SECURITY BOUNDARIES (Ceilings)
            MAX_ALLOWED_CPUS   = 16
            MAX_ALLOWED_MEMORY = 32 // Gigabytes
            
            // LOGISTIC BASELINE FALLBACKS (If integers or strings are omitted in YAML)
            DEFAULT_CPUS       = '4'
            DEFAULT_MEMORY     = '8g'
            DEFAULT_BUILD_CMD  = 'echo "No build command provided by developer"'
            
            // System Tools Integration
            SONAR_SCANNER_HOME = tool 'SonarQubeScanner' 
        }

        stages {
            stage('Ingest & Validate Configuration') {
                steps {
                    script {
                        // The Remote Jenkinsfile plugin guarantees this file exists
                        def config = readYaml file: 'pipeline.yaml'
                        
                        // Enforce mandatory structural fields
                        env.TEAM_FOLDER = config.pipeline.team_folder ?: error("Pipeline Aborted: 'team_folder' is a required field in your pipeline.yaml!")
                        
                        // Mandatory Image Enforcement (Strictly no fallback allowed)
                        if (!config.pipeline.container?.image) {
                            error "Pipeline Aborted: 'container.image' is required! Dev teams must explicitly specify their custom docker image version."
                        }
                        env.TARGET_IMAGE = config.pipeline.container.image
                        
                        // Capture Build execution commands
                        env.BUILD_CMD = config.pipeline.environment?.BUILD_COMMAND ?: env.DEFAULT_BUILD_CMD

                        // --- MULTI-LANGUAGE ARTIFACT CREATION CHECK (IF/ELSE) ---
                        def lowerCmd = env.BUILD_CMD.toLowerCase()
                        if (lowerCmd.contains("package") || lowerCmd.contains("install") || lowerCmd.contains("deploy") || 
                            lowerCmd.contains("build") || lowerCmd.contains("setup.py bdist") || lowerCmd.contains("cargo build")) {
                            echo "📦 Artifact generation detected. Enforcing security scanners (Trivy & SonarQube)..."
                            env.RUN_SECURITY_SCANS = "true"
                        } else {
                            echo "ℹ️ Baseline verification detected (No artifact generated). Skipping security scanners..."
                            env.RUN_SECURITY_SCANS = "false"
                        }

                        // --- STRICT HARD CEILING BOUNDARY ENFORCEMENT ---
                        def requestedCpus = (config.pipeline.container?.cpus ?: env.DEFAULT_CPUS).toInteger()
                        def requestedMem  = (config.pipeline.container?.memory ?: env.DEFAULT_MEMORY).replaceAll(/[gG]/, "").toInteger()
                        
                        if (requestedCpus > env.MAX_ALLOWED_CPUS.toInteger()) {
                            error "Pipeline Aborted: Requested CPUs (${requestedCpus}) exceeds policy maximum of ${env.MAX_ALLOWED_CPUS}!"
                        }
                        env.FINAL_CPUS = requestedCpus

                        if (requestedMem > env.MAX_ALLOWED_MEMORY.toInteger()) {
                            error "Pipeline Aborted: Requested Memory (${requestedMem}g) exceeds policy maximum of ${env.MAX_ALLOWED_MEMORY}g!"
                        }
                        env.FINAL_MEM = "${requestedMem}g"
                        
                        // Clean calculation of Swap mapping
                        def rawSwap = config.pipeline.container?.memory_swap ?: "${env.FINAL_MEM}"
                        env.FINAL_SWAP = rawSwap.contains("g") ? rawSwap : "${rawSwap}g"
                    }
                }
            }

            stage('Execute Multitenant Secure Workload') {
                steps {
                    withDockerRegistry([credentialsId: "${REGISTRY_CRED}"]) {
                        script {
                            // Compile dynamic resource runtime flags for the Docker daemon
                            def dynamicArgs = "--cpus='${env.FINAL_CPUS}' --memory='${env.FINAL_MEM}' --memory-swap='${env.FINAL_SWAP}' -v maven-repo:/root/.m2"
                            
                            echo "Spinning up image locally on Jump Host: ${env.TARGET_IMAGE}"
                            
                            docker.image("${env.TARGET_IMAGE}").inside(dynamicArgs) {
                                
                                // Conditional Execution: Trivy
                                if (env.RUN_SECURITY_SCANS == "true") {
                                    stage('Trivy Image Scan') {
                                        echo "🛡️ Running Trivy vulnerability scan on user-provided image..."
                                        sh "trivy image --severity HIGH,CRITICAL --exit-code 1 ${env.TARGET_IMAGE}"
                                    }
                                }

                                // Native Execution of Dev Team's Command
                                stage('Compile & Test Execution') {
                                    echo "🏗️ Launching pipeline compilation tasks..."
                                    sh "${env.BUILD_CMD}"
                                }

                                // Conditional Execution: SonarQube
                                if (env.RUN_SECURITY_SCANS == "true") {
                                    stage('SonarQube Static Analysis') {
                                        echo "🔍 Project Context: Injecting SonarQube quality gates..."
                                        withSonarQubeEnv('MySonarQubeServer') {
                                            sh "${env.SONAR_SCANNER_HOME}/bin/sonar-scanner -Dsonar.projectKey=${env.TEAM_FOLDER} -Dsonar.sources=."
                                        }
                                    }
                                }

                                // --- DYNAMIC INCLUDE PATTERN ---
                                // Finds and imports src/<team_folder>/CustomLogic.groovy on the fly
                                try {
                                    echo "🚀 Routing dynamic post-actions to library source: src/${env.TEAM_FOLDER}/CustomLogic.groovy"
                                    def teamClass = this.class.classLoader.loadClass("${env.TEAM_FOLDER}.CustomLogic").newInstance(this)
                                    teamClass.execute()
                                } catch (ClassNotFoundException e) {
                                    echo "No CustomLogic.groovy found inside src/${env.TEAM_FOLDER}/. Skipping custom post-build hooks."
                                }
                            }
                        }
                    }
                }
            }
        }
        
        post {
            always {
                // Multi-language Test Coverage Tracking Framework
                junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml, **/test-results/**/*.xml, **/junit.xml, **/build/test-results/**/*.xml'
            }
        }
    }
}

