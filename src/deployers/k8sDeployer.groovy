package deployers

class K8sDeployer implements Serializable {
    def steps
    
    K8sDeployer(steps) {
        this.steps = steps
    }
    
    def deploy(Map deployConfig) {
        steps.echo "Targeting Kubernetes deployment workspace..."
        
        // Bind the specific credential target provided by the team configuration
        steps.withKubeConfig([credentialsId: deployConfig.cluster_credential_id]) {
            steps.sh "kubectl apply -f k8s/deployment.yaml -n ${deployConfig.namespace || 'default'}"
        }
    }
}
