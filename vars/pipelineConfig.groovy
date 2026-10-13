#!/usr/bin/groovy

def call(body) {
    // ==========================================
    // 1. ADMIN CENTRAL CONFIGURATIONS & GUARDRAILS
    // ==========================================
    final String ARTIFACTORY_HOST = "192.168.122.154:5000" 
    final String SECURE_SCAN_IMAGE = "aquasec/trivy:latest" 
    final double MAX_CPU = 16.0
    final double MAX_MEMORY_GB = 32.0

    // Target the single massive administrator agent
    node('remote-node-4-ci') {
        currentBuild.result = "SUCCESS"

        stage('Checkout & Validation') {
            // SCM checkout happens automatically first
            checkout scm 
            
            if (!fileExists('pipeline-config.yaml')) {
                error "CRITICAL ERROR: 'pipeline-config.yaml' is missing from the repository root."
            }
            
            // Native Jenkins plugin step to parse YAML
            def config = readYaml file: 'pipeline-config.yaml'
            
            // Extract resource allocations (supporting decimal CPU formatting like "0.10")
            def requestedCpu = (config.resources?.cpu ?: "2").toDouble()
            def requestedMemStr = config.resources?.memory ?: "4Gi"

            // Compute actual requested size in GB for safety validation
            double requestedMemGB = 0.0
            String cleanMemStr = requestedMemStr.toUpperCase()
            double numericalValue = cleanMemStr.replaceAll(/[^\d\.]/, '').toDouble()

            if (cleanMemStr.contains("M")) {
                requestedMemGB = numericalValue / 1024.0 // Convert MB to GB
            } else if (cleanMemStr.contains("G")) {
                requestedMemGB = numericalValue         // Already in GB
            } else {
                requestedMemGB = numericalValue / (1024.0 * 1024.0 * 1024.0) // Assume raw bytes if no suffix
            }

            // Admin Safety Boundary Check (Enforced dynamically in GB/Doubles)
            if (requestedCpu > MAX_CPU || requestedMemGB > MAX_MEMORY_GB) {
                error "SECURITY VIOLATION: Requested resources (${requestedCpu} CPU / ${requestedMemStr}) exceed Admin safety limits (Max: ${MAX_CPU} CPU / ${MAX_MEMORY_GB}Gi)."
            }

            // Convert Kubernetes style '100Mi' or '8Gi' to Docker CLI compliant '100m' or '8g'
            String dockerMemoryFlag = requestedMemStr.toLowerCase().replaceAll(/i/, '')

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

            // ==========================================
            // FIX: MAP DEVELOPER STRINGS TO REAL UPSTREAM DOCKER TAGS
            // ==========================================
            def upstreamImageMap = [
                'java-1.17': 'maven:3.9.6-eclipse-temurin-17',
                'java-1.21': 'maven:3.9.6-eclipse-temurin-21',
                'go-1.21':   'golang:1.21-bookworm',
                'python-3':  'python:3.11-slim',
                'rust-1':    'rust:1.75-slim'
            ]
            
            String versionKey = config.stackVersion?.toLowerCase()
            String lookupString = "${stackKey}-${versionKey}"
            
            // Map input strings to real public upstream images
            String upstreamTarget = upstreamImageMap[lookupString] ?: upstreamImageMap["${stackKey}-1.21"]
            if (!upstreamTarget) { error "UNSUPPORTED STACK COMBINATION: ${lookupString}" }

            // This is the target name we use to spin up the local worker container
            String buildRuntimeImage = "${ARTIFACTORY_HOST}/${baseImageName}:${config.stackVersion}"

            // Explicitly pull the public upstream image name first, then tag it locally
            sh """
                docker image inspect ${buildRuntimeImage} >/dev/null 2>&1 || {
                    echo "Base image missing locally. Fetching ${upstreamTarget} from public registry..."
                    docker pull ${upstreamTarget}
                    docker tag ${upstreamTarget} ${buildRuntimeImage}
                    echo "Successfully pulled and tagged build environment image as ${buildRuntimeImage}"
                }
            """

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

            // Unique container runtime name to prevent overlap collisions
            String containerName = "jenkins-build-${env.JOB_NAME.replaceAll(/[^a-zA-Z0-9]/, '-')}-${env.BUILD_NUMBER}"
            
            // ==========================================
            // 2. CONTAINERIZED STACK EXECUTION
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
                // Guaranteed safety cleanup block to ensure agent is never choked by orphaned containers
                sh "docker rm -f ${containerName} || true"
            }

            // ==========================================
            // 3. MANDATORY ADMIN SECURITY COMPLIANCE
            // ==========================================
            stage('Mandatory Security Scan') {
                echo "Running required filesystem security auditing via central tooling..."
                String scanContainerName = "jenkins-scan-${env.BUILD_NUMBER}"
                try {
                    sh "docker run --rm --name ${scanContainerName} -v ${env.WORKSPACE}:/apps ${SECURE_SCAN_IMAGE} fs --exit-code 1 --severity HIGH,CRITICAL /apps"
                } catch (Exception e) {
                    error "SECURITY COMPLIANCE FAILURE: Vulnerabilities discovered by admin tool. Blocking artifact delivery."
                }
            }

            // ==========================================
            // 4. IMAGE DISTRIBUTION & PUBLISHING
            // ==========================================
            stage('Publish to Artifactory') {
                if (!config.imageName) { error "Configuration Error: 'imageName' must be provided to publish artifacts." }
                
                String targetImage = "${ARTIFACTORY_HOST}/${config.imageName}:${env.BRANCH_NAME}-${env.BUILD_NUMBER}"
                echo "Compiling final application image: ${targetImage}"
                
                // Call handler to synthesize its native tech-specific Dockerfile structure dynamically
                handler.packageAndPublish(targetImage, ARTIFACTORY_HOST)
                
                echo "Successfully delivered image payload to local Artifactory registry."
            }
        }
    }
}

