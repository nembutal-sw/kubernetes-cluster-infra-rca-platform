terraform {
  required_providers {
    vsphere = {
      source  = "vmware/vsphere"
      version = "~> 2.0"
    }
  }
}

provider "vsphere" {
  # 실습용 자체 서명 인증서 허용
  allow_unverified_ssl = true
}

# Datacenter 조회
data "vsphere_datacenter" "dc" {
  name = "Datacenter"
}

# Cluster 조회
data "vsphere_compute_cluster" "cluster" {
  name          = "Infra-Cluster"
  datacenter_id = data.vsphere_datacenter.dc.id
}

# NFS Datastore 조회
data "vsphere_datastore" "nfs" {
  name          = "shared-nfs"
  datacenter_id = data.vsphere_datacenter.dc.id
}

# VM Network 조회
data "vsphere_network" "network" {
  name          = "VM Network"
  datacenter_id = data.vsphere_datacenter.dc.id
}

# 템플릿 조회
data "vsphere_virtual_machine" "ubuntu_template" {
  name          = "ubuntu-2404-template"
  datacenter_id = data.vsphere_datacenter.dc.id
}

# 조회 결과 출력
output "vsphere_inventory" {
  value = {
    datacenter_id    = data.vsphere_datacenter.dc.id
    cluster_id       = data.vsphere_compute_cluster.cluster.id
    resource_pool_id = data.vsphere_compute_cluster.cluster.resource_pool_id
    datastore_id     = data.vsphere_datastore.nfs.id
    network_id       = data.vsphere_network.network.id
  }
}

output "ubuntu_template_info" {
  value = {
    id           = data.vsphere_virtual_machine.ubuntu_template.id
    guest_id     = data.vsphere_virtual_machine.ubuntu_template.guest_id
    firmware     = data.vsphere_virtual_machine.ubuntu_template.firmware
    scsi_type    = data.vsphere_virtual_machine.ubuntu_template.scsi_type
    disk_size    = data.vsphere_virtual_machine.ubuntu_template.disks[0].size
    network_type = data.vsphere_virtual_machine.ubuntu_template.network_interface_types[0]
  }
}
