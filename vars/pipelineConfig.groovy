#!/usr/bin/groovy

def call(body) {
    // ==========================================
    // 1. ADMIN CENTRAL CONFIGURATIONS & GUARDRAILS
    // ==========================================
    final String ARTIFACTORY_HOST = "192.168.122.154:5000" 
    final String SECURE_SCAN_IMAGE = "${ARTIFACTORY_HOST}/aquasec/trivy:latest" 
    final double MAX_CPU = 16.0
    final double MAX_MEMORY_GB = 32.0

    node('remote-node-4-ci') {
        currentBuild.result = "SUCCESS"

        stage('Checkout & Validation') {
            checkout scm 
            
            if (!fileExists('pipeline-config.yaml')) {
                error "CRITICAL ERROR: 'pipeline-config.yaml' is missing from the repository root."
            }
            
            def config = readYaml file: 'pipeline-config.yaml'
            
            // Extract and validate resource boundaries
            def requestedCpu = (config.resources?.cpu ?: "2").toDouble()
            def requestedMemStr = config.resources?.memory ?: "4Gi"
            double requestedMemGB = 0.0
            String cleanMemStr = requestedMemStr.toUpperCase()
            double numericalValue = cleanMemStr.replaceAll(/[^\d\.]/, '').toDouble()

            if (cleanMemStr.contains("M")) { requestedMemGB = numericalValue / 1024.0 }
            else if (cleanMemStr.contains("G")) { requestedMemGB = numericalValue }
            else { requestedMemGB = numericalValue / (1024.0 * 1024.0 * 1024.0) }

            if (requestedCpu > MAX_CPU || requestedMemGB > MAX_MEMORY_GB) {
                error "SECURITY VIOLATION: Requested resources exceed Admin safety limits."
            }

            String dockerMemoryFlag = requestedMemStr.toLowerCase().replaceAll(/i/, '')

            // Map stack strings to baseline official Docker images
            def imageMap = ['java': 'maven', 'go': 'golang', 'python': 'python', 'rust': 'rust']
            String stackKey = config.stack?.toLowerCase()
            String baseImageName = imageMap[stackKey]
            
            if (!baseImageName) { 
                error "UNSUPPORTED STACK: '${config.stack}' is not registered." 
            }
            
            String buildRuntimeImage = "${ARTIFACTORY_HOST}/${baseImageName}:${config.stackVersion}"

            // ==========================================
            // DYNAMIC REFLECTION RESOLUTION
            // ==========================================
            def handler = null
            String className = "handlers.${stackKey.capitalize()}Handler"
            try {
                Class handlerClass = this.class.classLoader.loadClass(className)
                handler = handlerClass.newInstance(this)
            } catch (ClassNotFoundException e) {
                error "CRITICAL CONFIGURATION ERROR: Helper class ${className} could not be resolved."
            }

            // Unique container runtime name to prevent collisions
            String containerName = "jenkins-build-${env.JOB_NAME.replaceAll(/[^a-zA-Z0-9]/, '-')}-${env.BUILD_NUMBER}"
            
            // ==========================================
            // 2. LIFECYCLE DELEGATION TO HANDLERS
            // ==========================================
            try {
                // Provision container using --net=host to easily bypass individual proxy setups
                sh "docker run -d --name ${containerName} --net=host --cpus=${requestedCpu} --memory=${dockerMemoryFlag} -v ${env.WORKSPACE}:${env.WORKSPACE} -w ${env.WORKSPACE} ${buildRuntimeImage} tail -f /dev/null"
                
                stage('Pre-Build Steps') {
                    handler.preBuild(containerName, config)
                }
                stage('Build') {
                    handler.build(containerName, config)
                }
                stage('Test') {
                    handler.test(containerName, config)
                }
            } finally {
                // Always clean up the workspace node container environment
                sh "docker rm -f ${containerName} || true"
            }

            // ==========================================
            // 3. MANDATORY ADMIN COMPLIANCE SCANS
            // ==========================================
            stage('Mandatory Security Scan') {
                String scanContainerName = "jenkins-scan-${env.BUILD_NUMBER}"
                try {
                    sh "docker run --rm --name ${scanContainerName} -v ${env.WORKSPACE}:/apps ${SECURE_SCAN_IMAGE} fs --exit-code 1 --severity HIGH,CRITICAL /apps"
                } catch (Exception e) {
                    error "SECURITY COMPLIANCE FAILURE: Vulnerabilities discovered by admin tool."
                }
            }

            // ==========================================
            // 4. PACKAGING AND DISTRIBUTION
            // ==========================================
            stage('Publish to Artifactory') {
                String targetImage = "${ARTIFACTORY_HOST}/${config.imageName}:${env.BRANCH_NAME}-${env.BUILD_NUMBER}"
                
                // Call handler to synthesize its native tech-specific Dockerfile structure dynamically
                handler.packageAndPublish(targetImage, ARTIFACTORY_HOST)
            }
        }
    }
}

