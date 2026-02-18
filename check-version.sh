#!/bin/bash
# Script to check version information

echo "=== UserService Version Information ==="
echo ""

# Check if service is running
if command -v kubectl &> /dev/null; then
    echo "Checking via Kubernetes service..."
    kubectl -n caritas exec -it deployment/oriso-platform-userservice -c userservice -- \
        curl -s http://localhost:8080/version 2>/dev/null || \
        echo "Service not accessible via kubectl"
    echo ""
    echo "Detailed info:"
    kubectl -n caritas exec -it deployment/oriso-platform-userservice -c userservice -- \
        curl -s http://localhost:8080/version/info 2>/dev/null || \
        echo "Service not accessible via kubectl"
else
    echo "kubectl not found. Please access the service directly:"
    echo "  curl http://localhost:8080/version"
    echo "  curl http://localhost:8080/version/info"
fi

echo ""
echo "=== Expected Versions ==="
echo "Java: 21"
echo "Spring Boot: 4.0.1"
echo "Spring Framework: 6.2.0"

