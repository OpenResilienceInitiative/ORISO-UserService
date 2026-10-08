# Image release security gate

UserService builds the image once and checks every target architecture before
publishing it. A fixable HIGH or CRITICAL finding, scanner error or transport
error stops publication. Pull requests and feature branches use the same gate
without logging in to the registry or publishing images.

The shared Docker action exports an untagged OCI archive with build provenance
and SBOM attestations. Skopeo selects each platform into a temporary OCI layout
for Trivy. Selection is explicit because Trivy can scan the first image in an
OCI index even when another platform was requested. The gate retains the
existing Trivy 0.70.0 policy for OS and library vulnerabilities.

After all platforms pass, Skopeo copies the original archive, including all
platform and attestation manifests, with digest preservation. It applies the
branch or release tags and checks that the published digest matches the build.
The existing provenance signing step runs only after this succeeds. Versioned
release branches keep their existing amd64 target and create the Git tag and
GitHub release only after the image gate succeeds.

CI needs a Linux runner with Skopeo and Trivy; the shared action installs them.
Gate contracts run in the existing Python CI suite with pinned PyYAML 6.0.3.
A green image gate does not prove that Dev runs that image or that its required
configuration and user flows work. Deployment and acceptance remain separate.

Refs [UserService1185](https://github.com/OpenResilienceInitiative/ORISO-UserService/issues/1185).
