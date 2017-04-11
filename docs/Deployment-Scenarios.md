# Example deployment scenarios

There are a four basic deployment scenarios that are supported by this playbook. In the first two (shown below) we'll walk through the deployment of Spark to a single node and the deployment of a multi-node Spark cluster using a static inventory file. In the third scenario, we will show how the same multi-node Spark cluster deployment shown in the second scenario could be performed using the dynamic inventory scripts for both AWS and OpenStack instead of a static inventory file. Finally, in the last scenario, we'll walk through the process of "growing" an existing Spark cluster by adding worker nodes to it.

## Scenario #1: deploying Spark to a single node
While this is the simplest of the deployment scenarios that are supported by this playbook, it is more than likely that deployment of Spark to a single node is really only only useful for very simple test environments. Even the most basic (default) Spark deployments that are typically shown in online examples of how to deploy Spark are two-node deployments.  Nevertheless, we will start our discussion with this deployment scenario since it is the simplest.

If we want to deploy Spark to a single node with the IP address "192.168.34.82", we could simply create a very simple inventory file that looks something like the following:

```bash
$ cat single-node-inventory

192.168.34.82 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/spark_node_private_key'

$ 
```

Note that in this example inventory file the `ansible_ssh_host` and `ansible_ssh_port` will take their default values since they aren't specified for our host in this very simple static inventory file. Once we've built our static inventory file, we can then deploy Spark to our single node by running an `ansible-playbook` command that looks something like this:

```bash
$ ansible-playbook -i single-node-inventory -e "{ host_inventory: ['192.168.34.82'] }" site.yml
```

This will download the Apache Spark distribution file from the default download server defined in the [vars/spark.yml](../vars/spark.yml) file, unpack that gzipped tarfile into the `/opt/apache-spark` directory on that host, and install Spark on that node and configure that node as a single-node Spark "cluster", using the default configuration parameters that are defined in the [vars/spark.yml](../vars/spark.yml) file.

## Scenario #2: deploying a multi-node Spark cluster
If you are using this playbook to deploy a multi-node Spark cluster, then the configuration becomes a bit more complicated. The Spark cluster actually consists of nodes with two different roles, a set of one or more master nodes that are responsible for deploying jobs to a larger set of worker (non-master) nodes. In addition to needing to know which nodes are master nodes and which are worker nodes, all nodes in the cluster need to be configured similarly so that all of them can agree (as part of that configuration) on how they should communicate with each other. It is in this scenario that support for a *local variables file* in the `dn-spark` role becomes important.

Let's assume that we are deploying Spark to a cluster of three nodes (one master node and two worker nodes) and, furthermore, let's assume that we're going to be using a static inventory file to control this deployment. The static inventory file that we will be using for this example looks like this:

```bash
$ cat test-cluster-inventory
# example inventory file for a clustered deployment

192.168.34.88 ansible_ssh_host=192.168.34.88 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/spark_cluster_private_key'
192.168.34.89 ansible_ssh_host=192.168.34.89 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/spark_cluster_private_key'
192.168.34.90 ansible_ssh_host=192.168.34.90 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/spark_cluster_private_key'
192.168.34.91 ansible_ssh_host=192.168.34.91 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/spark_cluster_private_key'
192.168.34.92 ansible_ssh_host=192.168.34.92 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/spark_cluster_private_key'
192.168.34.93 ansible_ssh_host=192.168.34.93 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/spark_cluster_private_key'

$
```

The playbook defined in the [site.yml](../site.yml) file in this repository supports the deployment of a cluster using this static inventory file, but the deployment is actually broken up into two separate `ansible-playbook` runs:

* In the first playbook run, Spark is deployed to the master node and the `spark-master` service is started on that node
* In the second playbook run, Spark is deployed to the two worker nodes, and those nodes are configured to talk to the master node in order to join the cluster

When the two `ansible-playbook` runs are complete, we will have a set of three nodes that are all working together as a cluster, with the master node distributing jobs to the worker nodes in the cluster as they are received. For purposes of this example, let's assume that we want to setup the first node listed in the `test-cluster-inventory` static inventory file (above) as our master node and the next two nodes in that inventory file as the worker nodes nodes.

To deploy our Spark to our master node, we'd run a command that looks something like this:

```bash
$ ansible-playbook -i test-cluster-inventory -e "{ \
      host_inventory: ['192.168.34.88'], \
      cloud: vagrant, spark_master_nodes: ['192.168.34.88'], \
      data_iface: eth0, api_iface: eth1, \
      spark_url: 'http://192.168.34.254/apache-spark/spark-2.1.0-bin-hadoop2.7.tgz', \
      yum_repo_url: 'http://192.168.34.254/centos', spark_data_dir: '/data' \
    }" site.yml
```

Alternatively, rather than passing all of those arguments in on the command-line as extra variables, we can make use of the *local variables file* support that is built into this playbook and construct a YAML file that looks something like this containing the configuration parameters that are being used for this deployment:

```yaml
cloud: vagrant
spark_master_nodes:
    - '192.168.34.88'
data_iface: eth0
api_iface: eth1
spark_url: 'http://192.168.34.254/apache-spark/spark-2.1.0-bin-hadoop2.7.tgz'
yum_repo_url: 'http://192.168.34.254/centos'
spark_data_dir: '/data'
```

and then we can pass in the *local variables file* as an argument to the `ansible-playbook` command; assuming the YAML file shown above was in the current working directory and was named `test-cluster-deployment-params.yml`, the resulting command would look somethin like this:

```bash
$ ansible-playbook -i test-cluster-inventory -e "{ \
      host_inventory: ['192.168.34.88'], \
      local_vars_file: 'test-cluster-deployment-params.yml' \
    }" site.yml
```

Once that playbook run is complete, we can browse to the Web UI provided by our master node (which can be found at the URL `http://192.168.34.88:8080` in this example) to view the detailed status of the the master node (it's `URL`, `REST URL`, number of `Alive Workers`, number of `Cores in use`, amount of `Memory in use`, number of `Applications` running and complete, etc.).

Now that our master node is up and running, we can run the second `ansible-playbook` command (making use of the same `local_vars_file` we used when deploying the master nodes, above) to deploy Spark to our worker nodes:

```bash
$ ansible-playbook -i test-cluster-inventory -e "{ \
      host_inventory: ['192.168.34.89', '192.168.34.90'], \
      local_vars_file: 'test-cluster-deployment-params.yml' \
    }" site.yml
```

When this second playbook run is complete, the worker nodes will register with the master node and we'll be able to see those nodes under the list of `Workers` the master node's Web UI (at `http://192.168.34.88:8080` in this example). In addition, we can also browse to the Web UI provided by each of the worker nodes (at `http://192.168.34.89:8181` and `http://192.168.34.90:8181`, respectively) to view their detailed status (their `ID` in the cluster, the configured `Master URL`, number of `Cores`, available `Memory`, and the list of `Running Executors` (which should be empty since no jobs have been deployed to the cluster yet).

This pair of `ansible-playbook` commands deployed Spark to all three of our nodes and configured them as a single cluster. Currently there is no support in this playbook for growing an existing cluster, but that is functionality we plan to add at a later date. 

## Scenario #3: deploying a Spark cluster via dynamic inventory
In this section we will repeat the multi-node cluster deployment that we just showed in the previous scenario, but we will use the dynamic inventory scripts provided in the [common-utils](../common-utils) submodule to control the deployment of our Spark cluster to an AWS or OpenStack environment rather than relying on a static inventory file.

To accomplish this, the we have to:

* Tag the master and worker instances in the AWS or OpenStack environment that we will be configuring as a cluster with the appropriate `Tenant`, `Project`, `Domain`, and `Application` tags. Note that for all of the nodes we are targeting in our playbook runs, we will assign an `Application` tag of `spark`; the master nodes will be assigned a `Role` tag of `master`
* Once all of the nodes that will make up our cluster have been tagged appropriately, we can run `ansible-playbook` command similar to the first `ansible-playbook` command shown in the previous scenario; this will deploy Spark to the initial set of (master) nodes. It is important to note that in this `ansible-playbook` command, the worker nodes must be included in the `skip_nodes_list` value passed into the `ansible-playbook` command. This will ensure that the deployment will only target the master nodes in the cluster; without this argument, the master nodes would not be targeted for deployment and the playbook would deploy Spark to the worker nodes, instructing them to talk to the non-existent master nodes in order to join a non-existent Spark cluster
* Once the master nodes are up and running, the same `ansible-playbook` command that we just ran to deploy Spark to those master nodes will be re-run, but this time without listing the worker nodes in the `skip_nodes_list`; this will deploy Spark to the remaining (worker) nodes in the cluster and instruct them to talk to the master nodes in order to join the cluster.

As was the case with the static inventory example described in the previous scenario, it takes two passes to deploy our cluster. In the first pass we deploy Spark to the master nodes, while in the second we deploy Spark to the worker nodes. When the two passes are complete, we have a working Spark cluster with a mix of master and worker nodes.

As an aside, if we wanted to make the two `ansible-playbook` commands for our two passes identical, we could modify the sequence of events sketched out, above, to look more like this:

* First, the master instances in the AWS or OpenStack environment that make up the cluster that we are deploying would be tagged with an `Application` tag of `spark` and a `Role` tag of `master`; the worker nodes **would be left untagged** for the moment (i.e. neither an `Application` tag nor a `Role` tag would be assigned to these nodes)
* Once the master nodes had been tagged appropriately, we could run our `ansible-playbook` command. Note that in this sequence of events there would be no need to add a `skip_nodes_list` value to the `ansible-playbook` command used for the first pass of our deployment since there would be no matching worker nodes that need to be skipped (only the master nodes would be found by the [build-app-host-groups](../common-roles/build-app-host-groups) role based on the tags that were passed into the `ansible-playbook` command).
* After the first pass playbook run was complete, the worker nodes would be tagged with an `Application` tag of `spark`, and a second `ansible-playbook` command (identical to the first) would be run. In that playbook run, the [build-app-host-groups](../common-roles/build-app-host-groups) role would find a mix of both master and worker nodes, and the playbook would deploy Spark to the worker nodes, configuring them to talk to the master nodes in order to join the cluster.

While the effect of this second approach would be identical to the first, one may be preferred over the other depending on the environment you are deploying Spark into. For example, if you don't have administrative access to the nodes in order to tag them yourself, it might be easier to use the first approach since you would only have to ask an administrator to tag the machines once. On the other hand, if you can't easily determine the IP addresses for the worker nodes ahead of time, then the second approach mignt be easier since you wouldn't have to know the IP addresses of the machines that should be skipped during the first run. Both scenarios are sketched out here so that you can choose the one that best suits your needs.

In terms of what the commands look like, lets assume for this example that we've tagged our master nodes with the following VM tags:

* **Tenant**: labs
* **Project**: projectx
* **Domain**: preprod
* **Application**: spark
* **Role**: master

and the worker nodes have been assigned the same set of `Tenant`, `Project`, `Domain`, and `Application` tags. The `ansible-playbook` command used to deploy Spark to our initial set of master nodes in an OpenStack environment would then look something like this (assuming that the IP addresses for the two worker nodes are '10.0.1.26', and '10.0.1.27'):

```bash
$ ansible-playbook -i common-utils/inventory/osp/openstack -e "{ \
        host_inventory: 'meta-Application_spark:&meta-Cloud_osp:&meta-Tenant_labs:&meta-Project_projectx:&meta-Domain_preprod', \
        skip_nodes_list: ['10.0.1.26', '10.0.1.27'], \
        application: spark, cloud: osp, tenant: labs, project: projectx, domain: preprod, \
        ansible_user: cloud-user, private_key_path: './keys', data_iface: eth0, api_iface: eth1, \
        spark_data_dir: '/data' \
    }" site.yml
```

once that playbook run was complete, you could simply re-run the same command (without the `skip_nodes_list` to deploy Spark to the worker nodes:

```bash
$ ansible-playbook -i common-utils/inventory/osp/openstack -e "{ \
        host_inventory: 'meta-Application_spark:&meta-Cloud_osp:&meta-Tenant_labs:&meta-Project_projectx:&meta-Domain_preprod', \
        application: spark, cloud: osp, tenant: labs, project: projectx, domain: preprod, \
        ansible_user: cloud-user, private_key_path: './keys', data_iface: eth0, api_iface: eth1, \
        spark_data_dir: '/data' \
    }" site.yml
```

In an AWS environment, the commands look quite similar; the command used for the first pass (provisioning the master nodes in the cluster) would look something like this (again, assuming that the IP addresses for the two worker nodes are '10.10.0.26', and '10.10.0.27'):

```bash
$ ansible-playbook -i common-utils/inventory/aws/ec2 -e "{ \
        host_inventory: 'tag_Application_spark:&tag_Cloud_aws:&tag_Tenant_labs:&tag_Project_projectx:&tag_Domain_preprod', \
        skip_nodes_list: ['10.0.1.26', '10.0.1.27'], \
        application: spark, cloud: aws, tenant: labs, project: projectx, domain: preprod, \
        ansible_user: cloud-user, private_key_path: './keys', data_iface: eth0, api_iface: eth1, \
        spark_data_dir: '/data' \
    }" site.yml
```

while the command used for the second pass (provisioning the worker nodes) would look something like this:

```bash
$ ansible-playbook -i common-utils/inventory/aws/ec2 -e "{ \
        host_inventory: 'tag_Application_spark:&tag_Cloud_aws:&tag_Tenant_labs:&tag_Project_projectx:&tag_Domain_preprod', \
        application: spark, cloud: aws, tenant: labs, project: projectx, domain: preprod, \
        ansible_user: cloud-user, private_key_path: './keys', data_iface: eth0, api_iface: eth1, \
        spark_data_dir: '/data' \
    }" site.yml
```

As you can see, they are basically the same commands that were shown for the OpenStack use case, but they are slightly different in terms of the name of the inventory script passed in using the `-i, --inventory-file` command-line argument, the value passed in for `Cloud` tag (and the value for the associated `cloud` variable), and the prefix used when specifying the tags that should be matched in the `host_inventory` value (`tag_` instead of `meta-`). In both cases the result would be a set of nodes deployed as a Spark cluster, with the number of nodes and their roles in the cluster determined (completely) by the tags that were assigned to them.

## Scenario #4: adding nodes to a multi-node Spark cluster
When adding nodes to an existing Spark cluster, we must be careful of a couple of things:

* We don't want to redeploy Spark to the existing nodes in the cluster, only to the new nodes we are adding
* We want to make sure the nodes being added are configured properly to join the cluster

In the case of adding worker nodes to an existing cluster, the process is relatively simple and basically looks like the second pass of either of the two cluster-based scenarios that were shown above. The only real difference is that:

* we need to add a `skip_nodes_list` value to the dynamic inventory scenario to ensure that the playbook doesn't try to redeploy Spark to the existing worker nodes in the cluster that we are adding our new nodes to.  For the static inventory use case, we just need to make sure that the list of `spark_master_nodes` is the same as the list that was used when deploying the initial cluster.

In both the static and dynamic use cases is it critical that the same configuration parameters be passed in during the deployment process that were used when building the initial cluster. The easiest way to manage this is to use a *local inventory file* to manage the configuration parameters that are used for a given cluster, then pass in that file as an argument to the `ansible-playbook` command that you are running to add nodes to that cluster. That said, in the examples we show (below) we will define the configuration parameters that were set to non-default values in the previous playbook runs as extra variables that are passed into the `ansible-playbook` command on the command-line for clarity.

To provide a couple of examples of how this process of growing a cluster works, this command could be used to add three new nodes to the existing Spark cluster that was created using the `test-cluster-inventory` (static) inventory file, above:

```bash
$ ansible-playbook -i test-cluster-inventory -e "{ \
      host_inventory: ['192.168.34.91', '192.168.34.92', '192.168.34.93'], \
      cloud: vagrant, spark_master_nodes: ['192.168.34.88'], \
      data_iface: eth0, api_iface: eth1, \
      spark_url: 'http://192.168.34.254/apache-spark/spark-2.1.0-bin-hadoop2.7.tgz', \
      yum_repo_url: 'http://192.168.34.254/centos', spark_data_dir: '/data' \
    }" site.yml
```

Or, if we wanted to take make use of the *local variables file* we defined, above, we could run this command instead:

```bash
$ ansible-playbook -i test-cluster-inventory -e "{ \
      host_inventory: ['192.168.34.91', '192.168.34.92', '192.168.34.93'], \
      local_vars_file: 'test-cluster-deployment-params.yml' \
    }" site.yml
```v

As you can see, these two commands are essentially the same commands we ran previously to add the initial set of two worker nodes to our cluster in the static inventory scenario. The only change to the previous commands is that the `host_inventory` extra variable has been modified to contain the IP addresses for the three new worker nodes we're adding to the cluster.

As an example of the dynamic inventory use case, this command could be used to add a new set of nodes to our OpenStack cluster (the number of nodes would depend on the number of worker nodes that matched the tags that were passed in):

```bash
$ ansible-playbook -i common-utils/inventory/osp/openstack -e "{ \
        host_inventory: 'meta-Application_spark:&meta-Cloud_osp:&meta-Tenant_labs:&meta-Project_projectx:&meta-Domain_preprod', \
        skip_nodes_list: ['10.0.1.26', '10.0.1.27'], \
        application: spark, cloud: osp, tenant: labs, project: projectx, domain: preprod, \
        ansible_user: cloud-user, private_key_path: './keys', data_iface: eth0, api_iface: eth1, \
        spark_data_dir: '/data' \
    }" site.yml
```

Note that in this dynamic inventory example we are once again using the `skip_nodes_list`, but this time we're using it to skip the worker nodes that we have already deployed Spark to (and that are already members of our cluster).

It should be noted that the playbook associated with this role does not currently support the process of adding new master nodes to an existing cluster, only the process of adding worker nodes. Adding a new master node (or nodes) involves modifying the configuration on every node in the cluster in order to add the new master node (or nodes) to the list of master nodes that is defined locally. This requires that each node be taken offline, it's configuration modified, then brought back online. That process is hard (at best) to automate, so we have made no effort to do so in the current version of this playbook.

# Closing thoughts
As was mentioned elsewhere, this playbook does not currently support deployment of multi-master Spark clusters, so only the first node in the `spark_master_nodes` list (whether defined statically or constructed dynamically by the [build-app-host-groups](../common-roles/build-app-host-groups) role) will be configured as a master for the cluster. To support multi-master Spark deployments (something that we want to do in the near future) it will be necessary to add support for integration of the Spark master nodes with an external Zookeeper ensemble. For now, only a single master should be defined when deploying Spark clusters.