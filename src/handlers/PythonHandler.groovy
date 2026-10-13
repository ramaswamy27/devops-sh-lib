package handlers

class PythonHandler implements Serializable {
    def steps
    
    PythonHandler(steps) {
        this.steps = steps
    }

    def preBuild() {
        steps.echo "Cleaning up local virtual environments and bytecode..."
        steps.sh "find . -type f -name '*.pyc' -delete"
    }
}
