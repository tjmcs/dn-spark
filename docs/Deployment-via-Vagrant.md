# Deployment via Vagrant
A [Vagrantfile](../Vagrantfile) is included in this repository that can be used to deploy Spark locally (to one or more VMs hosted under [VirtualBox](https://www.virtualbox.org/)) using [Vagrant](https://www.vagrantup.com/).  From the top-level directory of this repository a command like the following will (by default) deploy Spark to a single CentOS 7 virtual machine running under VirtualBox:

```bash
$ vagrant -s="192.168.34.82" up
```

Note that the `-s, --spark-list` flag must be used to pass an IP address (or a comma-separated list of IP addresses) into the [Vagrantfile](../Vagrantfile). In the example shown above, we are performing a single-node deployment of Spark, and that node will be configured as a master node.

When we are performing a multi-node deployment, then the master nodes for the cluster must identified, as in this example:

```bash
$ vagrant -s="192.168.34.88,192.168.34.89,192.168.34.90" -m="192.168.34.88" up
```

This command will create a three-node Spark cluster, where the first node in the list of Spark IP addresses (passed in using the `-s, --spark-list` flag) is configured as the master node (the list of master nodes is passed in using the `-m, --master-nodes` flag). It should be noted here that if any of the IP addresses listed in the list of master nodes does not also appear in the list of Spark IP addresses, an error will be thrown. Similarly, if there is more than one Spark IP address listed and a list of master nodes is not provided, an error will be thrown by the `vagrant` command.

As was noted in our discussion of the various supported [deployment scenarios](Deployment-Scenarios.md), the playbook in this repository can be used to perform multi-master Spark cluster deployments, and the [Vagrantfile](../Vagrantfile) in this repository supports that same functionality. When deploying a multi-master Spark cluster deployment, a Zookeeper inventory file for the Zookeeper ensemble that the master nodes should use to communicate with each other is required, and this inventory file can take one of two forms. It could either just contain a list of the nodes in the Zookeeper ensemble and the information needed to connect to those nodes:

```bash
$ cat zookeeper_inventory
# example inventory file for a clustered deployment

192.168.34.18 ansible_ssh_host=127.0.0.1 ansible_ssh_port=2200 ansible_ssh_user='vagrant' ansible_ssh_private_key_file='/tmp/dn-zookeeper/.vagrant/machines/192.168.34.18/virtualbox/private_key'
192.168.34.19 ansible_ssh_host=127.0.0.1 ansible_ssh_port=2201 ansible_ssh_user='vagrant' ansible_ssh_private_key_file='/tmp/dn-zookeeper/.vagrant/machines/192.168.34.19/virtualbox/private_key'
192.168.34.20 ansible_ssh_host=127.0.0.1 ansible_ssh_port=2202 ansible_ssh_user='vagrant' ansible_ssh_private_key_file='/tmp/dn-zookeeper/.vagrant/machines/192.168.34.20/virtualbox/private_key'

[zookeeper]
192.168.34.18
192.168.34.19
192.168.34.20

$
```

Given this static inventory file for the Zookeeper ensemble we want to use with our Spark cluster, a command like the following could be used to build a new four node Spark cluster consisting of two master nodes and two worker nodes:

```bash
$ vagrant -s="192.168.34.88,192.168.34.89,192.168.34.90,192.168.34.91" \
    -m="192.168.34.88,192.168.34.89" -i='./zookeeper_inventory' up
```

If a Zookeeper inventory file is not provided when building a multi-master Spark cluster, or if the file passed in does not contain the information needed to connect to one or more Kafka nodes, then an error will be thrown by the `vagrant` command.

In terms of how it all works, the [Vagrantfile](../Vagrantfile) is written in such a way that the following sequence of events occurs when the `vagrant ... up` command shown above is run:

1. All of the virtual machines in the cluster (the addresses in the `-s, --spark-list`) are created
1. Spark is deployed to the master nodes (the nodes with addresses in the `-m, --master-nodes` list) using one of the plays in the Ansible playbook in the [provision-spark.yml](../provision-spark.yml) file in this repository; if we are performing a multi-master deployment then the inventory information in the `zookeeper_inventory_file` that is passed into the playbook is used to obtain the information needed to configure these master nodes to talk with each other throught that Zookeeper ensemble
1. The `spark-master` service is started on all of the (master) nodes that were just provisioned
1. Spark then is deployed to the worker nodes by a second play in the same Ansible playbook; the list of master nodes is used to configure each of those worker nodes to talk to the master nodes and join the cluster when they start up
1. Finally, the `spark-worker` service is started on all of the (worker) nodes that were just provisioned

Once the first play in deployment process is complete, we can browse to the Web UI provided by any of our master nodes (either `http://192.168.34.88:8080` or `http://192.168.34.89:8080` in this example) to view the detailed status of the the master node (it's `URL`, `REST URL`, number of `Alive Workers`, number of `Cores in use`, amount of `Memory in use`, number of `Applications` running and complete, etc.).

Once the second play is complete and the `spark-worker` service has been started on the worker nodes, those nodes register themselves with the active master node to join the cluster. When those two steps are complete, we will be able to see those nodes under the list of `Workers` in the active master node's Web UI (either `http://192.168.34.88:8080` or `http://192.168.34.89:8080` in this example). In addition, we can also browse to the Web UI provided by each of the worker nodes (at `http://192.168.34.90:8181` and `http://192.168.34.91:8181`, respectively) to view their detailed status (their `ID` in the cluster, the configured `Master URL`, number of `Cores`, available `Memory`, and the list of `Running Executors` (which should be empty since no jobs have been deployed to the cluster yet).

So, to recap, by using a single `vagrant ... up` command we were able to quickly spin up a cluster consisting of of four Spark nodes (two master nodes and two worker nodes), and a similar `vagrant ... up` command could be used to build a cluster consisting of any number of master and worker nodes.

## Separating instance creation from provisioning
While the `vagrant up` commands that are shown above can be used to easily deploy Spark to a single node or to build a Spark cluster consisting of multiple nodes, the [Vagrantfile](../Vagrantfile) included in this distribution also supports separating out the creation of the virtual machine from the provisioning of that virtual machine using the Ansible playbook contained in this repository's [provision-spark.yml](../provision-spark.yml) file.

To create a set of virtual machines that we plan on using to build a Spark cluster without provisioning Spark to those machines, simply run a command similar to the following:

```bash
$ vagrant -s="192.168.34.88,192.168.34.89,192.168.34.90,192.168.34.91" up --no-provision
```

This will create a set of six virtual machines with the appropriate IP addresses ("192.168.34.88", "192.168.34.89", "192.168.34.90", and "192.168.34.91"), but will skip the process of provisioning those VMs with an instance of Spark. Note that when you are creating the virtual machines but skipping the provisioning step it is not necessary to provide the list of master nodes using the `-m, --master-nodes` flag.

To provision the machines that were created above and configure those machines as a Spark cluster, we simply need to run a command like the following:

```bash
$ vagrant -s="192.168.34.88,192.168.34.89,192.168.34.90,192.168.34.91" \
    -m="192.168.34.88,192.168.34.89" -i='./zookeeper_inventory' provision
```

That command will attach to the named instances and run the playbook in this repository's [provision-spark.yml](../provision-spark.yml) file on those node (first on the master nodes, then on the worker nodes), resulting in a Spark cluster consisting of the nodes that were created in the `vagrant ... up --no-provision` command that was shown, above.

## Additional vagrant deployment options
While the commands shown above will install Spark with a reasonable, default configuration from a standard location, there are additional command-line parameters that can be used to override the default values that are embedded in the [vars/spark.yml](../vars/spark.yml) file. Here is a complete list of the command-line flags that can be included in any `vagrant ... up` or `vagrant ... provision` command:

* **`-s, --spark-list`**: the Spark address list; this is the list of nodes that will be created and provisioned, either by a single `vagrant ... up` command or by a `vagrant ... up --no-provision` command followed by a `vagrant ... provision` command; this command-line flag **must** be provided for almost every `vagrant` command supported by the [Vagrantfile](../Vagrantfile) in this repository
* **`-m, --master-nodes`**: a comma-separated list of the nodes in the Spark address list that should be deployed as master nodes for the cluster being built; this argument **must** be provided for any `vagrant` commands that involve provisioning of the instances that make up a Spark cluster
* **`-i, --inventory-file`**: the path to a static inventory file containing the parameters needed to connect to the nodes that make up the associated Zookeeper ensemble; this argument **must** be provided for any `vagrant` commands that involve provisioning of the instances that make up a multi-master Spark cluster
* **`-u, --url`**: the URL that the Apache Spark distribution should be downloaded from. This flag can be useful in situations where there is limited (or no) internet access; in this situation the user may wish to download the distribution from a local web server rather than from the standard Spark download site
* **`-p, --path`**: the path to the directory that the Spark distribution should be unpacked into during the provisioning process; this defaults to the `/opt/apache-spark` directory if not specified
* **`-d, --data`**: the path to the directory where Spark will store it's data; this defaults to `/var/lib` if not specified
* **`-v, --version`**: the version of Spark that should be downloaded and installed; this parameter is only useful when downloading Spark from the standard Spark download site
* **`-y, --yum-url`**: the local YUM repository URL that should be used when installing packages during the node provisioning process. This can be useful when installing Spark onto CentOS-based VMs in situations where there is limited (or no) internet access; in this situation the user might want to install packages from a local YUM repository instead of the standard CentOS mirrors. It should be noted here that this parameter is not used when installing Spark on RHEL-based VMs; in such VMs this option will be silently ignored if set
* **`-f, --local-vars-file`**: the *local variables file* that should be used when deploying the cluster. A local variables file can be used to maintain the configuration parameter definitions that should be used for a given Spark cluster deployment, and values in this file will override any values that are either embedded in the [vars/spark.yml](../vars/spark.yml) file as defaults or passed into the `ansible-playbook` command as extra variables
* **`-c, --clear-proxy-settings`**: if set, this command-line flag will cause the playbook to clear any proxy settings that may have been set on the machines being provisioned in a previous ansible-playbook run. This is useful for situations where an HTTP proxy might have been set incorrectly and, in a subsequent playbook run, we need to clear those settings before attempting to install any packages or download the Spark distribution without an HTTP proxy

As an example of how these options might be used, the following command will download the gzipped tarfile containing the Apache Spark distribution from a local web server, rather than downloading it from the main Apache distribution site, and override the default configuration parameter definitions in this repository with those defined in the `projectx.yml` file (a project-specific *local variables file*) when provisioning the machines created by the `vagrant ... up --no-provision` command shown above:

```bash
$ vagrant -s="192.168.34.88,192.168.34.89,192.168.34.90" -m="192.168.34.88" \
    -u="https://10.0.2.2/spark-2.1.0-bin-hadoop2.7.tgz" \
    -f="projectx.yml" provision
```

Obviously, while the list of command-line parameters shown above cannot easily be extended to support all of the configuration options that can be set for any given Spark deployment, it is entirely possible to include any of the configuration parameters that need to be set in a *local variables file*, then pass them in as is shown above using the `-f, --local-vars-file` command-line option to the `vagrant ... up` or `vagrant ... provision` command (like the example `vagrant ... provision` command that is shown, above).
