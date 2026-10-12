package deployers // Updated match for the new flat path

class K8sDeployer implements Serializable {
  def steps
    
  K8sDeployer(steps) {
    this.steps = steps
  }
    
  def deploy(Map config) {
    steps.echo "Deploying to Kubernetes Cluster: ${config.cluster_credential_id}"
    steps.withKubeConfig([credentialsId: config.cluster_credential_id]) {
      steps.sh "kubectl apply -f k8s/ -n ${config.namespace || 'default'}"
    }
  }
}
