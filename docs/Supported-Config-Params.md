# Supported configuration parameters
The playbook in the [site.yml](../site.yml) file in this repository pulls in a set of default values for many of the configuration parameters that are needed to deploy Spark from the [vars/spark.yml](../vars/spark.yml) file. The parameters defined in these files define a reasonable set of defaults for a fairly generic Spark deployment, either to a single node or a cluster, including defaults for the URL that the Spark distribution should be downloaded from, the directory the distribution should be unpacked into, and the packages that must be installed on the node before the `spark-master` and `spark-worker` services can be started.

In addition to the defaults defined in the [vars/spark.yml](../vars/spark.yml) file, there are a large number of parameters that can be used to either control the deployment of Spark to the nodes that will make up a cluster during an `ansible-playbook` run or to configure those Spark nodes once the installation is complete. In this section, we summarize all of these options, breaking them out into:

* parameters used to control the `ansible-playbook` run
* parameters used during the deployment process itself, and
* parameters used to configure our Spark nodes once Spark has been installed locally.

Each of these sets of parameters are described in their own section, below.

## Parameters used to control the playbook run
The following parameters can be used to control the `ansible-playbook` run itself, defining things like how Ansible should connect to the nodes involved in the playbook run, which nodes should be targeted, where the Spark distribution should be downloaded from, which packages must be installed during the deployment process, and where those packages should be obtained from:

* **`ansible_ssh_private_key_file`**: the location of the private key that should be used when connecting to the target nodes via SSH; this parameter is useful when there is one private key that is used to connect to all of the target nodes in a given playbook run
* **`ansible_user`**: the username that should be used when connecting to the target nodes via SSH; is useful if the same username is used when connecting to all of the target nodes in a given playbook run
* **`spark_url`**: the URL that the Spark distribution should be downloaded from
* **`spark_version`**: the version of Spark that should be downloaded; used to switch versions when the distribution is downloaded using the default `spark_url`, which is defined in the [vars/spark.yml](../vars/spark.yml) file
* **`cloud`**: the name of the cloud type being targeted (`aws`, `osp`, or `vagrant`); this controls whether the inventory information for the playbook run is assumed to be passed in dynamically (when the cloud is `aws` or `osp`) or statically (if the cloud type is `vagrant`)
* **`host_inventory`**: used to pass in a list of the nodes targeted for deployment (in the static inventory use case) or a union of the application, tenant, project, and domain tags (in the dynamic inventory use case)
* **`local_vars_file`**: used to define the location of a *local variables file* (see the discussion of this topic, below); this file is a YAML file containing definitions for any of the configuration parameters that are described in this section and is more than likely a file that will be created to manage the process of creating a specific cluster (or adding nodes to that cluster). Storing the settings for a given cluster in such a file makes it easy to guarantee that all of the nodes in that cluster are configured consistently
* **`private_key_path`**: used to define the directory where the private keys are maintained when the inventory for the playbook run is being managed dynamically; in these cases, the scripts used to retrieve the dynamic inventory information will return the names of the keys that should be used to access each node, and the playbook will search the directory specified by this parameter to find the corresponding key files. If this value is not specified then the current working directory will be searched for those keys by default
* **`proxy_env`**: a hash map that is used to define the proxy settings to use for downloading distribution files and installing packages; supports the `http_proxy`, `no_proxy`, `proxy_username`, and `proxy_password` fields as part of this hash map
* **`reset_proxy_settings`**: used to reset any HTTP/YUM proxy settings that may have been made in a previous playbook run back to the defaults (no proxy); this is useful when a proxy was incorrectly set in a previous playbook run and the user wants to return to a "no-proxy" setup in the current playbook run
* **`skip_nodes_list`**: this parameter is used in the dynamic inventory use cases to pass in a list of hosts that *should not* be targeted by the deployment even though they have tags that match. This parameter is can be quite useful for situations where we are deploying the initial set of master nodes for a cluster; in that situation we need to skip the worker nodes during the first pass and only deploy Spark to the master nodes. This parameter is also useful in situations where we're managing our inventory dynamically and we want to add new nodes to an existing cluster. In that situation we will want to skip all of the existing (worker) nodes in the cluster and only deploy Spark to the new nodes. In both of these situations, we'll have to add the existing worker nodes (which would match based on the tags assigned to them) to the `skip_nodes_list` that we pass into the `ansible-playbook` command so that they won't be targeted by the playbook run.
* **`yum_repo_url`**: used to set the URL for a local YUM mirror. This parameter is only used for CentOS-based deployments; when deploying Spark to RHEL-based nodes this parameter is silently ignored and the RHEL package repositories defined locally on the node will be used for any packages installed during the deployment process

## Parameters used during the deployment process
These parameters are used to control the deployment process itself, defining things like where to unpack the distribution into, whether or not Spark should be started when the deployment process is complete, and what user/group the should be used when running Spark locally.

* **`spark_dir`**: the directory that the Spark distribution should be unpacked into; defaults to the `/opt/apache-spark` directory. If necessary, this directory will be created as part of the playbook run
* **`spark_package_list`**: the list of packages that should be installed on the Spark nodes; typically this parameter is left unchanged from the default (which installs the OpenJDK packages needed to run Spark), but if it is modified the default, OpenJDK packages must be included as part of this list or an error will result when attempting to start the `spark-master` and `spark-worker` services
* **`spark_group`**: the name of the user group under which Spark should be installed and run; defaults to `spark`
* **`spark_user`**: the username under which Spark should be installed and run; defaults to `spark`

## Parameters used to configure the Spark nodes
These parameters are used configure the Spark nodes themselves during a playbook run, defining things like the interfaces that Spark should be listening on for requests, the directory where Spark should store its data, and the list of worker nodes for the cluster.

* **`data_iface`**: the name of the interface that the Spark nodes should use when talking with each other. This interface typically corresponds to a private or management network, with no customer access. An interface of this name must exist for the playbook to run successfully, and if unspecified a value of `eth0` is assumed
* **`api_iface`**: the name of the interface that the Spark nodes should use when handling user requests. This network corresponds to a public network since customers will use this interface to access the data in the Spark cluster. An interface of this name must exist for the playbook to run successfully, and if unspecified a value of `eth0` is assumed
* **`iface_description_array`**: this parameter can be used in place of the `data_iface` and `api_iface` parameters described above, and it provides users with the ability to specify a description of these two interfaces rather than identifying them by name (more on this, below)
* **`spark_data_dir`**: the name of the directory that Spark should use to store its data; defaults to `/var/lib` if unspecified. If necessary, this directory will be created as part of the playbook run
* **`spark_master_nodes`**: this parameter is used to pass in the list of master nodes for the cluster; it is required for any multi-node (clustered) Spark deployment and should be consistently defined for all nodes in the cluster

## Interface names vs. interface descriptions
For some operating systems on some platforms, it can be difficult (if not impossible) to determine the names of the interfaces that should be passed into the playbook using the `data_iface` and `api_iface` parameters that we described, above. In those situations, the playbook in this repository provides an alternative; specifying those interfaces using the `iface_description_array` parameter instead.

Put quite simply, the `iface_description_array` lets you specify a description for each of the networks that you are interested in, then retrieve the names of those networks on each machine in a variable that can be used elsewhere in the playbook. To accomplish this, the `iface_description_array` is defined as an array of hashes (one per interface), each of which include the following fields:

* **`type`**: the type of description being provided, currently only the `cidr` type is supported
* **`val`**: a value describing the network in question; since only `cidr` descriptions are currently supported, a CIDR value that looks something like `192.168.34.0/24` should be used for this field
* **`as_var`**: the name of the variable that you would like the interface name returned as

With these values in hand, the playbook will search the available networks on each machine and return a list of the interface names for each network that was described in the `iface_description_array` as the value of the fact named in the `as_var` field for that network's entry. For example, given this description:

```json
    iface_description_array: [
        { as_var: 'data_iface', type: 'cidr', val: '192.168.34.0/24' },
        { as_var: 'api_iface', type: 'cidr', val: '192.168.44.0/24' },
    ]
```

the playbook will return the name of the network that matches the CIDR `192.168.34.0/24` as the value of the `data_iface` fact and the name of the network that matches the CIDR `192.168.34.0/24` as the value of the `api_iface` fact. These two facts can then be used later in the playbook to correctly configure the nodes to talk to each other and listen on the proper interfaces for user requests.

It should be noted that if you choose to take this approach when constructing your `ansible-playbook` runs, a matching entry in the `iface_description_array` must be specified for both the `data_iface` and `api_iface` networks, otherwise the default value of `eth0` will be used for these facts (and the playbook run may result in nodes that are at best misconfigured; if the `eth0` network does not exist then the playbook will fail to run altogether).
