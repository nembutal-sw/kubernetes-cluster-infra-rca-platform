# 온프레미스 Terraform · Ansible

control-01에서 사용하던 Terraform과 Ansible 코드를 2026-10-19에 보존한 사본입니다. Terraform 파일의 들여쓰기와 정렬만 `terraform fmt`으로 정리했습니다. 운영 서버의 파일과 인프라는 변경하지 않았습니다.

| 구성 | 코드 범위 |
| --- | --- |
| Terraform | 기존 vSphere 인벤토리 조회, Kubernetes VM 3대와 `ci-agent-01` VM 생성 |
| Ansible | 노드 공통 설정, containerd, Kubernetes 설치, Control Plane 초기화, Worker join, Calico 설치와 Ready 확인 |
| 별도 준비 | ESXi, vCenter, control-01, NFS, Ubuntu 템플릿, Jenkins, Podman, Registry와 애플리케이션 배포 |

## 파일 구성

| 경로 | 내용 |
| --- | --- |
| `terraform/main.tf` | `vmware/vsphere` provider와 기존 인벤토리·템플릿 조회 |
| `terraform/k8s-vms.tf` | `k8s-cp-01`, `k8s-worker-01`, `k8s-worker-02` |
| `terraform/ci-agent.tf` | `ci-agent-01` |
| `terraform/.terraform.lock.hcl` | vSphere provider `2.17.1` 의존성 고정 |
| `terraform/examples/vm.tf.example` | 초기 `tf-test-01`·`tf-test-02` 실습 예제. `.tf` 확장자가 아니므로 활성 구성에서 제외됨 |
| `ansible/inventory/hosts.ini` | Kubernetes 노드와 그룹 |
| `ansible/playbooks/k8s-prereq.yml` | common → containerd → kubernetes role |
| `ansible/playbooks/k8s-cluster.yml` | 사전 조건 확인 → Control Plane → Calico → Worker → 클러스터 검증 |
| `ansible/playbooks/bootstrap.yml` | 초기 `lab` 그룹 실습용. 현재 inventory에는 `lab` 그룹이 없음 |
| `ansible/roles/` | 공통 설정, 런타임, Kubernetes, Control Plane, Worker, Calico |
| `ansible/files/` | Registry 공개 CA 인증서와 containerd `hosts.toml` 참고 파일 |

Registry 참고 파일은 현재 playbook에서 배포하지 않습니다. Registry 인증과 containerd의 Registry 설정 경로도 별도로 구성해야 합니다. CA 인증서에는 개인키가 포함되지 않습니다.

## 원본 경로와 도구

- Terraform: control-01의 `/root/infra-platform/terraform/`
- Ansible: control-01의 `/home/ubuntu/infra-platform/ansible/`
- 확인한 도구: Terraform `1.16.6`, Ansible core `2.16.3`, vSphere provider `2.17.1`

Terraform은 기존 `Datacenter`, `Infra-Cluster`, `shared-nfs`, `VM Network`, `ubuntu-2404-template`을 조회합니다. 템플릿에는 Ubuntu 계정, SSH 접근, VMware Tools와 VM customization 지원이 준비돼 있어야 합니다.

Provider 인증은 `VSPHERE_SERVER`, `VSPHERE_USER`, `VSPHERE_PASSWORD` 환경변수로 전달합니다. 인증값은 저장소에 넣지 않습니다. 원본의 자체 서명 인증서 허용 설정 `allow_unverified_ssl = true`와 `/8` netmask, gateway `10.0.0.1`, DNS는 이 실습 환경의 설정입니다. 다른 환경에서 사용하기 전 주소·인증서 신뢰·네트워크 구성을 검토해야 합니다.

Ansible은 `ansible/` 디렉터리에서 실행하도록 상대 경로가 설정돼 있습니다. Inventory의 SSH 개인키 경로는 관리 호스트의 로컬 경로이며 키 파일은 포함하지 않습니다. 대상 Ubuntu 계정의 SSH 및 sudo 접근이 필요합니다.

containerd 설치는 대상 노드에 설정된 APT 공급원에 의존하며 role이 버전 `2.2.0` 이상을 확인합니다. Kubernetes는 `v1.36` 채널에서 설치 후 패키지를 hold하고, Calico는 `v3.33.0` 매니페스트를 다운로드합니다. 정확한 Kubernetes patch 버전을 고정한 구성은 아닙니다.

## 구성을 적용하지 않는 문법 확인

Terraform을 처음 내려받은 환경에서는 provider 설치 후 검증합니다. Provider가 문법 검사에서도 인증 입력을 요구하므로 아래 명령은 검증용 임시값을 사용합니다. 실제 자격정보와 vCenter 연결 없이 검증한 방법입니다.

```sh
cd infra/onprem/terraform
terraform init -backend=false -lockfile=readonly
terraform fmt -check
VSPHERE_SERVER=127.0.0.1 \
VSPHERE_USER=validation-only \
VSPHERE_PASSWORD=validation-only \
terraform validate
```

Ansible은 inventory와 playbook을 파싱하며 노드에 작업을 실행하지 않습니다.

```sh
cd infra/onprem/ansible
ansible-inventory --graph
ansible-playbook --syntax-check playbooks/k8s-prereq.yml
ansible-playbook --syntax-check playbooks/k8s-cluster.yml
```

실제 구성 순서는 `k8s-prereq.yml` 다음 `k8s-cluster.yml`입니다. 실행하면 노드 설정 변경, 패키지 설치, 초기화와 join이 수행됩니다. 현재 운영 환경의 재실행이나 신규 배포는 이 보존 작업에서 수행하지 않았습니다.

## 보존 범위

Terraform state·백업·plan·실제 tfvars, `.terraform/`, 비밀번호, 토큰, SSH 개인키, kubeconfig, Registry 인증파일과 CA 개인키는 제외했습니다. Worker join 토큰을 다루는 두 task에는 원본의 `no_log: true`가 유지돼 있습니다.

기존 state는 이 저장소에 포함되지 않습니다. 이 디렉터리를 기존 인프라에 새 state로 적용하면 중복 생성이나 상태 불일치가 발생할 수 있으므로 기존 state와 리소스 소유권 확인이 먼저 필요합니다.
