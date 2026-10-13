#!/usr/bin/groovy
import groovy.yaml.YamlSlurper
import handlers.JavaHandler
import handlers.GoHandler
import handlers.PythonHandler
import handlers.RustHandler

def call(body) {
    // ==========================================
    // 1. ADMIN CENTRAL CONFIGURATIONS & GUARDRAILS
    // ==========================================
    final String ARTIFACTORY_HOST = "192.168.122.154:5000" 
    final String SECURE_SCAN_IMAGE = "aquasec/trivy:latest" 
    final int MAX_CPU = 16
    final int MAX_MEMORY_GB = 32

    // Target the single massive administrator agent
    node('massive-agent') {
        currentBuild.result = "SUCCESS"

        stage('Checkout & Validation') {
            // SCM checkout happens automatically first
            checkout scm 
            
            // Read and parse the developer configuration payload
            if (!fileExists('pipeline-config.yaml')) {
                error "CRITICAL ERROR: 'pipeline-config.yaml' is missing from the repository root."
            }
            
            def yamlText = readFile('pipeline-config.yaml')
            def config = new YamlSlurper().parseText(yamlText)
            
            // Extract and validate container resource requests
            def requestedCpu = (config.resources?.cpu ?: "2").toInteger()
            def requestedMemStr = config.resources?.memory ?: "4Gi"
            def requestedMem = requestedMemStr.replaceAll(/[^\d]/, '').toInteger()

            if (requestedCpu > MAX_CPU || requestedMem > MAX_MEMORY_GB) {
                error "SECURITY VIOLATION: Requested resources (${requestedCpu} CPU / ${requestedMemStr}) exceed Admin safety limits (Max: ${MAX_CPU} CPU / ${MAX_MEMORY_GB}Gi)."
            }

            // Map stack strings to baseline official Docker images
            def imageMap = [
                'java': 'maven',
                'go': 'golang',
                'python': 'python',
                'rust': 'rust'
            ]
            
            String stackKey = config.stack?.toLowerCase()
            String baseImageName = imageMap[stackKey]
            
            if (!baseImageName) { 
                error "UNSUPPORTED STACK: '${config.stack}' is not registered in the central Admin library. Allowed: java, go, python, rust." 
            }
            
            // Formulate the runtime image using the user-defined version tag
            String buildRuntimeImage = "${baseImageName}:${config.stackVersion}"
            
            // Instantiate the correct language handler instance based on stack input
            def handler
            switch(stackKey) {
                case 'java':   handler = new JavaHandler(steps); break
                case 'go':     handler = new GoHandler(steps); break
                case 'python': handler = new PythonHandler(steps); break
                case 'rust':   handler = new RustHandler(steps); break
                default: 
                    error "CRITICAL FAILURE: Handler mapping missing for valid stack '${stackKey}'."
            }

            // ==========================================
            // 2. CONTAINERIZED STACK EXECUTION
            // ==========================================
            echo "Spinning up container runtime [${buildRuntimeImage}] with allocated limits: ${requestedCpu} CPU, ${requestedMemStr} Memory."
            
            docker.image(buildRuntimeImage).inside("--cpus=${requestedCpu} --memory=${requestedMemStr}") {
                
                stage('Pre-Build Steps') {
                    // Executes common baseline routines defined by the admin for that language
                    handler.preBuild() 
                }

                stage('Build') {
                    if (!config.buildCmd) { error "Configuration Error: 'buildCmd' is empty." }
                    sh "${config.buildCmd}"
                }
                
                stage('Test') {
                    if (!config.testCmd) { error "Configuration Error: 'testCmd' is empty." }
                    sh "${config.testCmd}"
                }
            }

            // ==========================================
            // 3. MANDATORY ADMIN SECURITY COMPLIANCE
            // ==========================================
            stage('Mandatory Security Scan') {
                echo "Running required filesystem security auditing via central tooling..."
                docker.image(SECURE_SCAN_IMAGE).inside() {
                    // Fails the pipeline instantly if High or Critical risk items are discovered
                    sh "trivy fs --exit-code 1 --severity HIGH,CRITICAL ."
                }
            }

            // ==========================================
            // 4. IMAGE DISTRIBUTION & PUBLISHING
            // ==========================================
            stage('Publish to Artifactory') {
                if (!config.imageName) { error "Configuration Error: 'imageName' must be provided to publish artifacts." }
                
                String targetImage = "${ARTIFACTORY_HOST}/${config.imageName}:${env.BRANCH_NAME}-${env.BUILD_NUMBER}"
                echo "Compiling final application image: ${targetImage}"
                
                // Build production-ready app container using the local repository Dockerfile
                def appImage = docker.build(targetImage, ".")
                
                docker.withRegistry("http://${ARTIFACTORY_HOST}") {
                    appImage.push()
                }
                echo "Successfully delivered image payload to local Artifactory registry."
            }
        }
    }
}
