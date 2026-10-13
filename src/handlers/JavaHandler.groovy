package handlers

class JavaHandler implements Serializable {
    def steps
    
    JavaHandler(steps) {
        this.steps = steps
    }

    def preBuild() {
        steps.echo "Configuring common Maven enterprise caching and proxies..."
    }
}
