# Example deployment scenarios

There are a five basic deployment scenarios that are supported by this playbook. In the first two (shown below) we'll walk through the deployment of Spark to a single node and the deployment of a multi-node Spark cluster using a static inventory file. In the third scenario, we will demonstrate how the deployment of a multi-master cluster differs from the single-master cluster deployment that is shown in the second scenario. In the fourth scenario, we will show how the same multi-node Spark cluster deployment shown in the second and third scenarios could be performed using the dynamic inventory scripts for both AWS and OpenStack instead of a static inventory file. Finally, we'll walk through the process of "growing" an existing cluster by adding nodes to it.

## Scenario #1: deploying Spark to a single node
While this is the simplest of the deployment scenarios that are supported by this playbook, it is more than likely that deployment of Spark to a single node is really only only useful for very simple test environments. Even the most basic (default) Spark deployments that are typically shown in online examples of how to deploy Spark are two-node deployments.  Nevertheless, we will start our discussion with this deployment scenario since it is the simplest.

If we want to deploy Spark to a single node with the IP address "192.168.34.82", we could simply create a very simple inventory file that looks something like the following:

```bash
$ cat single-node-inventory

192.168.34.82 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/spark_node_private_key'

[spark]
192.168.34.82

$ 
```

Note that in this example inventory file the `ansible_ssh_host` and `ansible_ssh_port` will take their default values since they aren't specified for our host in this very simple static inventory file. Once we've built our static inventory file, we can then deploy Spark to our single node by running an `ansible-playbook` command that looks something like this:

```bash
$ ansible-playbook -i single-node-inventory provision-spark.yml
```

This will download the Apache Spark distribution file from the default download server defined in the [vars/spark.yml](../vars/spark.yml) file, unpack that gzipped tarfile into the `/opt/apache-spark` directory on that host, and install Spark on that node and configure that node as a single-node Spark "cluster", using the default configuration parameters that are defined in the [vars/spark.yml](../vars/spark.yml) file.

## Scenario #2: deploying a multi-node Spark cluster
If you are using this playbook to deploy a multi-node Spark cluster, then the configuration becomes a bit more complicated. The Spark cluster actually consists of nodes with two different roles, a set of one or more master nodes that are responsible for deploying jobs to a larger set of worker (non-master) nodes. In addition to needing to know which nodes are master nodes and which are worker nodes, all nodes in the cluster need to be configured similarly so that all of them can agree (as part of that configuration) on how they should communicate with each other. It is in this scenario that support for a *local variables file* in the `dn-spark` role becomes important.

Let's assume that we are deploying Spark to a cluster of three nodes (one master node and two worker nodes) and, furthermore, let's assume that we're going to be using a static inventory file to control this deployment. The static inventory file that we will be using for this example looks like this:

```bash
$ cat combined-inventory
# example inventory file for a clustered deployment

192.168.34.88 ansible_ssh_host=192.168.34.88 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/spark_cluster_private_key'
192.168.34.90 ansible_ssh_host=192.168.34.90 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/spark_cluster_private_key'
192.168.34.91 ansible_ssh_host=192.168.34.91 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/spark_cluster_private_key'

[spark_master]
192.168.34.88

[spark]
192.168.34.90
192.168.34.91

$
```

The playbook defined in the [provision-spark.yml](../provision-spark.yml) file in this repository supports the deployment of a cluster using this static inventory file, but the playbook run is actually broken up into two separate plays:

* In the first play, Spark is deployed to the master node and the `spark-master` service is started on that nodes
* In the second play, Spark is deployed to the two worker nodes and those nodes are configured to talk to the master node in order to join the cluster; the `spark-worker` service is then started on those nodes

When the two plays in the `ansible-playbook` run are complete, we will have a set of three nodes that are all working together as a cluster, with one acting as the master node, distributing jobs to the worker nodes as those jobs are received.

The `ansible-playbook` command used to deploy Spark to the target nodes and configure them as a Spark cluster would look something like this:

```bash
$ ansible-playbook -i combined-inventory -e "{ \
      data_iface: eth0, api_iface: eth1, \
      spark_url: 'http://192.168.34.254/apache-spark/spark-2.1.0-bin-hadoop2.7.tgz', \
      yum_repo_url: 'http://192.168.34.254/centos', spark_data_dir: '/data' \
    }" provision-spark.yml
```

Alternatively, rather than passing all of those arguments in on the command-line as extra variables, we can make use of the *local variables file* support that is built into this playbook and construct a YAML file that looks something like this containing the configuration parameters that are being used for this deployment:

```yaml
data_iface: eth0
api_iface: eth1
spark_url: 'http://192.168.34.254/apache-spark/spark-2.1.0-bin-hadoop2.7.tgz'
yum_repo_url: 'http://192.168.34.254/centos'
spark_data_dir: '/data'
```

and then we can pass in the *local variables file* as an argument to the `ansible-playbook` command; assuming the YAML file shown above was in the current working directory and was named `test-cluster-deployment-params.yml`, the resulting command would look something like this:

```bash
$ ansible-playbook -i combined-inventory -e "{ \
      local_vars_file: 'test-cluster-deployment-params.yml' \
    }" provision-spark.yml
```

As an aside, it should be noted here that the [provision-spark.yml](../provision-spark.yml) playbook includes a [shebang](https://en.wikipedia.org/wiki/Shebang_(Unix)) line at the beginning of the playbook file. As such, the playbook can be executed directly as a shell script (rather than using the file as the final input to an `ansible-playbook` command). This means that the command that was shown above could also be run as:

```bash
$ ./provision-spark.yml -i test-cluster-inventory -e "{ \
      local_vars_file: 'test-cluster-deployment-params.yml' \
    }"
```

This form is available as a replacement for any of the `ansible-playbook` commands that we show here; which form you use will likely be a matter of personal preference (since both accomplish the same thing).

Once that playbook run is complete, we can browse to the Web UI provided by our master node (which can be found at the `http://192.168.34.88:8080` in this example) to view the detailed status of the the master node (it's `URL`, `REST URL`, number of `Alive Workers`, number of `Cores in use`, amount of `Memory in use`, number of `Applications` running and complete, etc.).

Once the worker nodes have registered with the master node, we will also be able to see those nodes under the list of `Workers` the master node's Web UI (at `http://192.168.34.88:8080`, for example). In addition, we can also browse to the Web UI provided by each of the worker nodes (at `http://192.168.34.89:8181` and `http://192.168.34.90:8181`, respectively) to view their detailed status (their `ID` in the cluster, the configured `Master URL`, number of `Cores`, available `Memory`, and the list of `Running Executors` (which should be empty since no jobs have been deployed to the cluster yet).

## Scenario #3: deploying a multi-master a Spark cluster
The deployment of a multi-master Spark cluster brings on additional complexity that is not required for a single-master Spark cluster. As was described elsewhere, a multi-master Spark cluster has two or more masters, but only one active master at any given time. The active master handles the process of distributing jobs to the worker nodes in the cluster. The other master node (or nodes) remain on standby in case the primary master node fails. The communications between the master nodes (for example, to select the active master initially, determine if a new active master needs to be elected from amongst the standby masters, or to check the state of health of the active master) all occur through an associated Zookeeper ensemble.

So that's the real difference between a single-master and multi-master Spark cluster deployment. For a single-master deployment (like the one we showed above, in the second scenario) there is no need for an associated Zookeeper ensemble, but in the case of a multi-master Spark cluster such a Zookeeper ensemble is required.

As is the case with the other playbooks we have written where a cluster is integrated with an associated Zookeeper ensemble (the [dn-kafka](https://github.com/Datanexus/dn-kafka), [dn-storm](https://github.com/Datanexus/dn-storm), and [dn-solr](https://github.com/Datanexus/dn-solr) playbooks, for example), the playbook will need to connect to the nodes that make up the associated Zookeeper ensemble and collect information from them, and to do so we'll have to pass in the information that Ansible will need to make those connections to the playbook. We can do this by creating an inventory file that contains the inventory information for the members of the cluster that we are building **as well as** the inventory information that Ansible needs to connect to the (external) Zookeeper ensemble that the master nodes of this Spark cluster will be using to communicate with each other. Here's an example of such a static inventory file:

```bash
$ cat combined-inventory
# example inventory file for a clustered deployment

192.168.34.88 ansible_ssh_host=192.168.34.88 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/spark_cluster_private_key'
192.168.34.89 ansible_ssh_host=192.168.34.89 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/spark_cluster_private_key'
192.168.34.90 ansible_ssh_host=192.168.34.90 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/spark_cluster_private_key'
192.168.34.91 ansible_ssh_host=192.168.34.91 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/spark_cluster_private_key'
192.168.34.92 ansible_ssh_host=192.168.34.90 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/spark_cluster_private_key'
192.168.34.93 ansible_ssh_host=192.168.34.91 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/spark_cluster_private_key'

192.168.34.18 ansible_ssh_host=192.168.34.18 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/zk_cluster_private_key'
192.168.34.19 ansible_ssh_host=192.168.34.19 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/zk_cluster_private_key'
192.168.34.20 ansible_ssh_host=192.168.34.20 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/zk_cluster_private_key'

[spark_master]
192.168.34.88
192.168.34.89

[spark]
192.168.34.90
192.168.34.91

[zookeeper]
192.168.34.18
192.168.34.19
192.168.34.20

$
```

Note that we now have three host groups in our static inventory file; one containing the master nodes, a second containing a list of the worker nodes, and a third identifying the members of the external Zookeeper ensemble we will be using for our deployment.

In either case, we'll pass in this inventory file using the `zookeeper_inventory_file` extra variable. For this scenario let's assume that we want to deploy our cluster using the combined static inventory file shown above. We can reuse the same `test-cluster-deployment-params.yml` file that was shown previously:

```yaml
data_iface: eth0
api_iface: eth1
spark_url: 'http://192.168.34.254/apache-spark/spark-2.1.0-bin-hadoop2.7.tgz'
yum_repo_url: 'http://192.168.34.254/centos'
spark_data_dir: '/data'
```

in that case, the command to deploy our multi-master cluster would look something like this:

```bash
$ ansible-playbook -i combined-inventory -e "{ \
      local_vars_file: 'test-cluster-deployment-params.yml' \
    }" provision-spark.yml
```

As before, once that playbook run is complete we can browse to the Web UI provided by our both of our master node (which can be found at `http://192.168.34.88:8080` and `http://192.168.34.89:8080` in this example) to view the detailed status of each of those master nodes (it's `URL`, `REST URL`, number of `Alive Workers`, number of `Cores in use`, amount of `Memory in use`, number of `Applications` running and complete, etc.). In doing so, we will see that one of the nodes has a `Status` of `ACTIVE` and the other has a `Status` of `STANDBY`.

Once the worker nodes have registered with the `ACTIVE` master node, we will also be able to see those nodes under the list of `Workers` in that master node's Web UI (either `http://192.168.34.88:8080` or `http://192.168.34.89:8080` in this example). In addition, as was the case with the previous scenario, we can also browse to the Web UI provided by each of the worker nodes (at `http://192.168.34.90:8181` and `http://192.168.34.91:8181`, respectively) to view their detailed status (their `ID` in the cluster, the configured `Master URL`, number of `Cores`, available `Memory`, and the list of `Running Executors` (which should be empty since no jobs have been deployed to the cluster yet).

## Scenario #4: deploying a Spark cluster via dynamic inventory
In this section we will repeat the multi-node cluster deployment that we just showed in the previous scenario, but we will use the `build-app-host-groups` role that is provided in the [common-roles](../common-roles) submodule to control the deployment of our Spark cluster (and integration of the master nodes in that cluster with an external Zookeeper ensemble) in an AWS or OpenStack environment rather than relying on a static inventory file.

To accomplish this, the we have to:

* Tag the master and worker instances in the AWS or OpenStack environment that we will be configuring as a cluster with the appropriate `Tenant`, `Project`, `Domain`, and `Application` tags. Note that for all of the nodes we are targeting in our playbook runs, we will assign an `Application` tag of `spark`; the master nodes will be assigned a `Role` tag of `master`
* Tag a set of nodes containing an existing Zookeeper ensemble with the same `Tenant`, `Project`, and `Domain` tags if we are deploying a multi-master Spark deployment. Obviously, the nodes that make up this Zookeeper ensemble would be tagged with an `Application` tag of `zookeeper`, and this playbook assumes that a Zookeeper ensemble has already been built by deploying Zookeeper to these nodes and configuring them as an ensemble. It should be noted that this Zookeeper ensemble is only needed of you are performing a multi-master Spark cluster deployment, for single-master Spark clusters an external Zookeeper ensemble is not needed.
* Once all of the nodes that will make up our cluster have been tagged appropriately, we can run `ansible-playbook` command similar to the first `ansible-playbook` command shown in the previous scenario; this playbook run will deploy Spark to all of the nodes in our cluster (both master and worker nodes) in a single playbook run and, if there is more than one master node, configure those master nodes to communicate with each other through the Zookeeper ensemble

In terms of what the command looks like, lets assume for this example that we've tagged our master nodes with the following VM tags:

* **Tenant**: labs
* **Project**: projectx
* **Domain**: preprod
* **Application**: spark
* **Role**: master

and the worker nodes have been assigned the same set of `Tenant`, `Project`, `Domain`, and `Application` tags, but they have not been assigned a `Role` tag of any sort. The `ansible-playbook` command used to deploy Spark to our cluster would look something like this:

```bash
$ ansible-playbook -e "{ \
        application: spark, cloud: osp, \
        tenant: labs, project: projectx, domain: preprod, \
        private_key_path: './keys', data_iface: eth0, api_iface: eth1, \
        spark_data_dir: '/data' \
    }" provision-spark.yml
```

In an AWS environment, the command that we would use looks quite similar:

```bash
$ AWS_PROFILE=datanexus_west ansible-playbook -e "{ \
        application: spark, cloud: aws, \
        tenant: labs, project: projectx, domain: preprod, \
        private_key_path: './keys', data_iface: eth0, api_iface: eth1, \
        spark_data_dir: '/data' \
    }" provision-spark.yml
```

As you can see, these two commands only in terms of the environment variable defined at the beginning of the command-line used to provision to the AWS environment (`AWS_PROFILE=datanexus_west`) and the value defined for the `cloud` variable (`osp` versus `aws`). In both cases the result would be a set of nodes deployed as a Spark cluster, with the master nodes in that cluster configured to talk to each other through the associated (assumed to already be deployed) Zookeeper ensemble. The number of nodes in the Spark cluster and their roles in that cluster will be determined (completely) by the number of nodes in the OpenStack or AWS environment that have been tagged with a matching set of `application`, `role` (for the master nodes), `tenant`, `project` and `domain` tags.

## Scenario #5: adding nodes to a multi-node Spark cluster
When adding nodes to an existing Spark cluster, we must be careful of a couple of things:

* We don't want to redeploy Spark to the existing nodes in the cluster, only to the new nodes we are adding
* We want to make sure the nodes we are adding to the cluster are configured properly to join that cluster

In the case of adding (non-master) nodes to an existing cluster, the process is relatively simple. To make matters simpler (and ensure that there is no danger of reprovisioning the nodes in the exiting cluster when attempting to add new nodes to it), we have actually separated out the plays that are used to add nodes to an existing cluster into a separate playbook (the [add-nodes.yml](./add-nodes.yml) file in this repository).

As was mentioned, above, it is critical that the same configuration parameters be passed in during the process of adding new nodes to the cluster as were passed in when building the cluster initially. Spark is not very tolerant of differences in configuration between members of a cluster, so we will want to avoid those situations. The easiest way to manage this is to use a *local inventory file* to manage the configuration parameters that are used for a given cluster, then pass in that file as an argument to the `ansible-playbook` command that you are running to add nodes to that cluster. That said, in the dynamic inventory examples we show (below) we will define the configuration parameters that were set to non-default values in the previous playbook runs as extra variables that are passed into the `ansible-playbook` command on the command-line for clarity.

To provide a couple of examples of how this process of growing a cluster works, we would first like to walk through the process of adding two new (non-master) nodes to the existing Spark cluster that was created using the `combined-inventory` (static) inventory file, above. The first step would be to edit the static inventory file and add the two new nodes to the `spark` host group, then save the resulting file. The host groups defined in the `combined-inventory` file shown above would look like this after those edits:

```
[spark_master]
192.168.34.88
192.168.34.89

[spark]
192.168.34.90
192.168.34.91
192.168.34.92
192.168.34.93

[zookeeper]
192.168.34.18
192.168.34.19
192.168.34.20
```

(note that we have only shown the tail of that file; the hosts defined at the start of the file would remain the same). With the new static inventory file in place, the playbook command that we would run to add the three additional nodes to our cluster would look something like this:

```bash
$ ./add-nodes.yml -i combined-inventory -e "{ \
      local_vars_file: 'test-cluster-deployment-params.yml' \
    }"
```

As you can see, this is essentially the same command we ran previously to provision our initial cluster in the static inventory scenario. The only change to the previous command are that we are using a different playbook (the [add-nodes.yml](../add-nodes.yml) playbook instead of the [provision-spark.yml](../provision-spark.yml) playbook).

To add new nodes to an existing Spark cluster in an AWS or OpenStack environment, we would simply create the new nodes we want to add in that environment and tag them appropriately (using the same `Tenant`, `Application`, `Project`, and `Domain` tags that we used when creating our initial cluster). With those new machines tagged appropriately, the command used to add a new set of (non-master) nodes to an existing cluster in an OpenStack environment would look something like this:

```bash
$ ansible-playbook -e "{ \
        application: spark, cloud: osp, \
        tenant: labs, project: projectx, domain: preprod, \
        private_key_path: './keys', data_iface: eth0, api_iface: eth1, \
        spark_data_dir: '/data' \
    }" add-nodes.yml
```

The only difference when adding nodes to an AWS environment would be the environment variable that needs to be set at the beginning of the command-line (eg. `AWS_PROFILE=datanexus_west`) and the cloud value that we define within the extra variables that are passed into that `ansible-playbook` command (`aws` instead of `osp`):

```bash
$ AWS_PROFILE=datanexus_west ansible-playbook -e "{ \
        application: spark, cloud: aws, \
        tenant: labs, project: projectx, domain: preprod, \
        private_key_path: './keys', data_iface: eth0, api_iface: eth1, \
        spark_data_dir: '/data' \
    }" add-nodes.yml
```

As was the case with the static inventory example shown above, the command shown here for adding new nodes to an existing cluster in an AWS or OpenStack cloud (using tags and dynamic inventory) is essentially the same command that was used when deploying the initial cluster, but we are using a different playbook (the [add-nodes.yml](../add-nodes.yml) playbook instead of the [provision-spark.yml](../provision-spark.yml) playbook).

It should be noted that the playbook associated with this role does not currently support the process of adding new master nodes to an existing Spark cluster, only adding non-master nodes. Adding a new master node (or nodes) involves modifying the Spark configuration on every node in the cluster in order to add the new master node (or nodes) to the list of master nodes defined there. This requires that each node's configuration be modified, then that each node be taken offline and brought back online to pick up the configuration change. That process is hard (at best) to automate, so we have made no effort to do so in the current version of this playbook.
