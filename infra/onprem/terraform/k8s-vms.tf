locals {
  k8s_nodes = {
    "k8s-cp-01" = {
      ip     = "10.7.254.10"
      memory = 4096
    }

    "k8s-worker-01" = {
      ip     = "10.7.254.11"
      memory = 4096
    }

    "k8s-worker-02" = {
      ip     = "10.7.254.12"
      memory = 4096
    }
  }
}

resource "vsphere_virtual_machine" "k8s" {
  for_each = local.k8s_nodes

  name             = each.key
  resource_pool_id = data.vsphere_compute_cluster.cluster.resource_pool_id
  datastore_id     = data.vsphere_datastore.nfs.id

  num_cpus = 2
  memory   = each.value.memory

  guest_id  = data.vsphere_virtual_machine.ubuntu_template.guest_id
  firmware  = data.vsphere_virtual_machine.ubuntu_template.firmware
  scsi_type = data.vsphere_virtual_machine.ubuntu_template.scsi_type

  network_interface {
    network_id = data.vsphere_network.network.id

    adapter_type = data.vsphere_virtual_machine.ubuntu_template.network_interface_types[0]
  }

  disk {
    label            = "disk0"
    size             = 40
    thin_provisioned = true
  }

  clone {
    template_uuid = data.vsphere_virtual_machine.ubuntu_template.id

    customize {
      linux_options {
        host_name = each.key
        domain    = "localdomain"
      }

      network_interface {
        ipv4_address = each.value.ip
        ipv4_netmask = 8
      }

      ipv4_gateway = "10.0.0.1"

      dns_server_list = [
        "8.8.8.8",
        "1.1.1.1"
      ]

      timeout = 30
    }
  }

  wait_for_guest_net_timeout = 10
}
