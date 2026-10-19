resource "vsphere_virtual_machine" "ci_agent" {
  name             = "ci-agent-01"
  resource_pool_id = data.vsphere_compute_cluster.cluster.resource_pool_id
  datastore_id     = data.vsphere_datastore.nfs.id

  num_cpus = 2
  memory   = 4096

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
        host_name = "ci-agent-01"
        domain    = "localdomain"
      }

      network_interface {
        ipv4_address = "10.7.254.6"
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
