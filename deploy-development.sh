#!/bin/bash
set -e
echo "🔨 Building UserService..."
cd /root/online-beratung/ORISO-Complete/caritas-workspace/ORISO-UserService

# Build the JAR (skip tests and test compilation)
echo "📦 Compiling and packaging..."
mvn clean package -DskipTests -Dmaven.test.skip=true -Dspotless.check.skip=true

# Ensure Docker build uses the freshly built JAR
cp -f target/UserService.jar UserService.jar

echo "🐳 Building Docker image..."
TIMESTAMP=$(date +%s)
IMAGE_TAG="oriso-userservice:dev-${TIMESTAMP}"
docker build -t ${IMAGE_TAG} .
docker tag ${IMAGE_TAG} oriso-userservice:latest

echo "📦 Importing image into k3s..."
docker save ${IMAGE_TAG} | sudo k3s ctr images import - > /dev/null 2>&1
docker save oriso-userservice:latest | sudo k3s ctr images import - > /dev/null 2>&1

echo "🚀 Restarting deployment..."
kubectl rollout restart deployment/oriso-platform-userservice -n caritas
kubectl rollout status deployment/oriso-platform-userservice -n caritas --timeout=120s

echo "✅ UserService deployed successfully!"
echo "📋 Checking pod status..."
kubectl get pods -n caritas | grep userservice

