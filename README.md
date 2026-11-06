# Onward Logistics Order Service

The containerized Onward Logistics order system: one Java 21 application that serves the order page (UI) and the order logic, packaged in one container image.

| Route | Purpose |
|---|---|
| `GET /` | Order page: builder's name, lab name, version, and which ECS task answered |
| `POST /orders` | Validates an order and prices the delivery |
| `GET /health` | Health check used by the load balancer |

## How a change reaches customers

Merging to `main` is the only manual step.

1. **GitHub Actions** ([.github/workflows/release.yml](.github/workflows/release.yml)) gets short-lived AWS credentials through **OIDC**. No AWS keys are stored in this repository or in GitHub.
2. It builds the image, uploads the deploy bundle (`taskdef.json` + `appspec.yaml`) to S3, then pushes the image to **Amazon ECR** with two tags: the short commit SHA and `latest`.
3. The ECR push raises an **EventBridge** event that starts **CodePipeline** (`onward-dev-order-pipeline`).
4. **CodeDeploy** runs the new version (green) next to the current one (blue), sends 10% of traffic to it for 5 minutes, then 100%. If the deployment fails it rolls back to blue automatically; blue tasks stay for 10 minutes so a rollback can also be started by hand.

## Files

| File | What it is |
|---|---|
| `OrderApp.java` | The whole application (no dependencies) |
| `Dockerfile` | Compiles the app with the JDK, runs it on a JRE as a non-root user |
| `taskdef.json` | ECS task definition template; CodePipeline replaces `<IMAGE1_NAME>`, the workflow sets the version |
| `appspec.yaml` | Tells CodeDeploy which container and port receive traffic |

## Run it locally

```bash
docker build -t onward-order-service .
docker run -p 8080:8080 -e FULL_NAME="Your Name" onward-order-service
```

## Where the infrastructure lives

All AWS resources (network, VPC endpoints, ECR, ECS service, load balancer, pipeline) are defined with CloudFormation in [onward-repo-infra](https://github.com/g1kdo/onward-repo-infra) under `delivery/`, deployed with GitSync.

## Rolling back

- **Automatic:** a failed deployment returns traffic to the previous version.
- **By hand:** in CodeDeploy, open the running deployment and choose **Stop and roll back**, or revert the commit and merge.
