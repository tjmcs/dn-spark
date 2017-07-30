# Securing the Cluster

There are two basic scenarios supported by the playbooks in this repository when it comes to securing a Spark cluster:

* The [provision-spark.yml](../provision-spark.yml) playbook can be used to deploy a secure Spark cluster directly or
* The [secure-spark.yml](../secure-spark.yml) playbook can be used to secure a Spark cluster that has already been deployed but is not yet secured (or to switch the mechanism used to secure the Spark cluster in question)

In both scenarios, the UI of the active master node is setup such that only authenticated users that are part of the access control lists configured on the master nodes during the playbook run can access the UI and view or modify jobs in the cluster (more on this, below).

In addition to setting up access controls for cluster, the playbooks in this repository also create a shared secret and use that shared secret to secure communications between the nodes in the cluster. The cluster also is configured so that the services in the cluster are listening on and communicating over the private network (i.e. all communications between the nodes take place over the `data_iface` network and the main services are listening on that same network), a virtual IP address is setup that points to the active master node (again, more detail on this process is available below), and the `iptables` package is used to configure port forwarding between this virtual IP address and services of the underlying cluster. Finally, the master nodes are configured to act as a reverse proxy for the underlying worker nodes, allowing for access to those worker nodes through the active master node's web UI.

## User Authentication

The current playbooks only support Basic Authentication (via a username/password collected through the browser). The playbooks in this repository could be extended in the future to support something more sophisticated (for example authentication via Kerberos, SAML, or via an external directory of some sort), but for now we feel that Basic Authentication will be sufficient. There are actually two different mechanisms supported in the playbooks in this repository for adding this Basic Authentication layer to the cluster:

* A [servlet filter](http://docs.oracle.com/javaee/6/api/javax/servlet/Filter.html) instance that performs Basic Authentication locally can be built and deployed to the cluster nodes during the playbook run and those nodes can then be configured (as part of the same playbook run) to use that servlet filter instance to authenticate users locally
* An external NGINX instance/cluster can be configured as a reverse proxy for the active master node, and that instance can be setup to authenticate the user based on information received from the browser as part of the HTTP request; if the user is successfully authenticated by the reverse proxy, the username associated with that request is then passed on to the active master node as part of the forwarded HTTP request

Both of these scenarios are supported by the two playbooks mentioned above (leading to four scenarios when it comes to securing a Spark cluster using the playbooks in this repository), and we will describe these two mechanisms for securing the UI via Basic Authentication in more detail in the sections that follow.

### Basic Authentication via the Active Master

In this scenario, the master nodes of the cluster are configured to perform their own Basic Authentication using a servlet filter instance that is built and deployed during the playbook run. This servlet filter compares the username and password received through the browser with the hashed password stored locally for that username on each node. The user is only granted access if the hash of the password they entered in the browser matches the hash stored locally on each node of the cluster for that username.

To make this scenario work, a set of username/password pairs must be generated during the playbook run and stored locally on each of the cluster nodes. To accomplish this, the playbooks take advantage of Ansible's [Lookup plugin](http://docs.ansible.com/ansible/latest/playbooks_lookups.html) to generate a new password on the Ansible host for each user, then use that password (along with the `htpasswd` command from the `httpd-tools` package) to create a file on the filesystem of each node in the Spark cluster containing the usernames that we are configuring access for along with a salted, hashed password value for each of those usernames.

The passwords that are generated for each username are stored in the `password.txt` file in the `credentials/{{username}}` subdirectory of the directory that the playbook was run from. Once this file has been created for each `username` we are configuring access for, the value in that file will be reused in subsequent playbook runs as the password for that same `username` (so if you want to regenerate a new password for a given `username` you should delete the corresponding `password.txt` file, if it exists, before re-running the playbook in question).

The passwords generated by the call made by our playbooks to Ansible's Lookup plugin are 15 characters in length and consist of ASCII letters, digits, and hexadecimal digits. If we decide in the future that this is something that we want to modify dynamically, it's a relatively simple matter to take the "recipe" for these passwords from a parameter that can be changed during the playbook run.

Once the playbook run has built this password file on each of the nodes in our cluster, the next step in the process of configuring basic authentication locally on our Spark nodes is to build and deploy a servlet filter that will compare the username/password values it receives as part of an HTTP request with the hashed password that is found in the this password file for that same username. The servlet filter that is used for this purpose is the `com.datanexus.servlet.BasicAuthFilter` class, which is built during the playbook run from the [BasicAuthFilter.java.j2](../roles/secure-spark-cluster/templates/BasicAuthFilter.java.j2) Jinja2 template and then bundled into a JAR file that is placed on the Spark server's classpath.

The last step in the process of securing the Spark UI using this servlet filter is simply to set the `spark.ui.filters` property in the `spark-defaults.conf` file to the value `com.datanexus.servlet.BasicAuthFilter`; this configures the server to use our servlet filter for all HTTP requests that it receives, ensuring that all users will have to successfully authentication with the Spark cluster nodes before being granted access to their UI.  For more detail on the steps in this process, users are encouraged to look through the comments in the [secure-spark-ui-local.yml](../roles/secure-spark-cluster/tasks/secure-spark-ui-local.yml) file.

### Basic Authentication via NGINX

In this scenario, an NGINX instance/cluster is configured as a reverse proxy for the Spark cluster, and users connect to the cluster through that reverse proxy. There are a couple of assumptions made in this scenario that should be explicitly stated here:

* First, there is an assumption that the NGINX instance/cluster has been configured in such a way that a user must successfully authenticate with that instance/cluster before accessing the content that it provides
* Second, there is an assumption that the usernames used to access the NGINX instance/cluster are part of the access control lists configured on the master nodes of the Spark cluster; if a username on the NGINX instance does not exist in the ACLs configured on the Spark cluster, that user will be denied access to the Spark UI (even though they successfully authenticated with the NGINX instance/cluster (this is done because the NGINX instance/cluster may actually be used as a reverse proxy for more than just the Spark cluster, so the usernames configured on that NGINX instance/cluster may not all be intended for use in accessing that Spark cluster).

Provided that the user successfully authenticates with the NGINX instance/cluster, the authentication information received by the instance/cluster will be passed on to the active master node. As was the case with the previous scenario, the information received from the NGINX instance/cluster must be parsed and placed into the request so that it can be retrieved by the Spark security manager. This is accomplished using the `com.datanexus.servlet.ForwardingAuthFilter` servlet filter, which simply parses the username and password that it receives, then uses those values to wrap the HTTP request that it received in a ServletRequestWrapper (the `com.datanexus.servlet.http.BasicAuthHttpServletRequest` class is used).

As was the case with the previous scenario, the classes mentioned here are built from Java files that are created from Jinja2 templates, then bundled into a JAR file and placed in a directory on the classpath of the Spark server instances as part of the playbook run. For more detail on the steps in this process, users are encouraged to look through the comments in the [secure-spark-ui-nginx.yml](../roles/secure-spark-cluster/tasks/secure-spark-ui-nginx.yml) file.

It should be noted that the `com.datanexus.servlet.ForwardingAuthFilter` servlet filter does not actually make a comparison of the username/password that it receives with any list of passwords that it is maintaining internally, it simply takes the username that it receives and uses it to setup the ServletRequestWrapper instance (so that it can be used internally by the Spark servers to check and see which username made a given HTTP request).

## Access Control Lists

In addition to securing the cluster via Basic Authentication, the playbooks in this repository also setup three access control lists that are used to control the actions that a given user can perform based on their assigned *role* in the process of managing or working with the cluster. In Apache Spark, there are three pre-defined user lists/groups:

* **`spark.admin.acls`**: the users in this (comma-separated) list have access permissions to view and modify all Spark jobs running in the cluster
* **`spark.modify.acls`** the users in this (comma-separated) list have access permissions to modify all Spark jobs running in the cluster
* **`spark.ui.view.acls`** the users in this (comma-separated) list have view access to the Spark Web UI

Membership in these three user groups is controlled in the playbooks in this repository via three optional parameters that can be defined during a playbook run, specifically: 

* **`spark_admin_users`**: the (comma-separated) list of the users who should be added to the `spark.admin.acls` list on the nodes that make up the Spark cluster being provisioned or secured
* **`spark_modify_users`**: the (comma-separated) list of the users who should be added to the `spark.modify.acls` list on the nodes that make up the Spark cluster being provisioned or secured
* **`spark_view_users`**: the (comma-separated) list of the users who should be added to the `spark.ui.view.acls` list on the nodes that make up the Spark cluster being provisioned or secured

It should be noted here that any users in the `spark_admin_users` and `spark_modify_users` lists will also be added to the `spark_view_users` list by default (so there is no need to add these users to multiple lists). In addition, a default value of `admin` is assumed for the `spark_admin_users` list if a value was not provided for this parameter, so there will always be at least one user with adminstrative rights.

There are a few considerations that should be taken into account when defining values for these three parameters:

* it is not necessary to define values for these parameters when securing the playbook (reasonable default values are defined if values are not passed into the playbook run as was discussed in the description of these parameters, above), but if values are provided for any (or all) of these three parameters then those values will override these default values
* when securing the UI locally via basic authentication (see the [Basic Authentication via the Active Master](#Basic-Authentication-via-the-Active-Master) section for more details on this), a unique set of usernames is constructed based on the users defined in these three lists, and access is configured for each user in that list of usernames
* when securing the UI via basic authentication using an NGINX instance/cluster (see the [Basic Authentication via NGINX](#Basic-Authentication-via-NGINX) section for more details on this), care should be taken to ensure that the usernames in these three lists are provided with access to the NGINX instance/cluster; the playbooks in this repository do not add users to the NGINX instance/cluster (since this is instance/cluster is more than likely a shared resource that is managed separately), so each user in these lists must be able to authenticate successfully with the NGINX instance/cluster in order to access the underlying Spark cluster.

## Providing access via a Virtual IP and port forwarding

## Example Deployment Scenarios

Given the general descriptions (above) of the various deployment scenarios supported by the playbooks in this repository, we felt that a few concrete examples might be helpful. In the examples that follow, we will be deploying a four-node Spark cluster with two master nodes. As was discussed [here](Deployment-Scenarios.md), both dynamic and static inventories can be used to drive these playbooks. To simplify our examples we will be using a static inventory file for all of the playbook runs shown here. The only difference (as will be discussed later) between the two static inventory files shown is whether or not an NGINX instance/cluster is defined as part of the static inventory file (it is for one of the inventory files we show here, but not for the other).

### Deployment of a cluster secured locally

In this example, we will provision a new secure cluster (where authentication is handled locally by the Spark nodes) using the [provision-spark.yml](../provision-spark.yml) playbook. To deploy a locally secured cluster, we must define values for a couple of additional parameters (above and beyond those that are defined when deploying an unsecured Spark cluster using this same playbook); specifically, we need to define the values for the following parameters:

* **`secure_cluster`**: a flag indicating whether or not we should secure the cluster we are deploying this flag defaults to `false` (so, by default, the -spark_master_virtual_ip playbook deploys an unsecured Spark cluser); as such, this flag must be set to `true` to deploy a new, secure cluster
* **`spark_master_virtual_ip`**: the IP address of the vitual IP that should be bound to the active master node by the `keepalived` daemon; an IP address must be provided for this parameter or the role that secures the Spark cluster will fail to execute (and the playbook run will not be successful)

In addition to these two parameters, values can also be provided for any (or all) of the three parameters that are used to control user membership in the ACLs that are configured during the playbook (see the [Access Control Lists](#Access-Control-Lists) section of this document for more details).  In this example we will setup three user accounts (one *admin* account, one *modify* account, and one *view* account) using the values defined in a local variables file for the three parameters described in that section of this document, but the details for this are not critical to the example.

The inventory file that we'll be using for our playbook run looks like this:

```
$ cat secure_local_multi_master_inventory
# assumes that there is already a three-node Zookeeper ensemble running

192.168.34.18 ansible_ssh_host=192.168.34.18 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/zk_cluster_private_key'
192.168.34.19 ansible_ssh_host=192.168.34.19 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/zk_cluster_private_key'
192.168.34.20 ansible_ssh_host=192.168.34.20 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/zk_cluster_private_key'

192.168.34.88 ansible_ssh_host=192.168.34.88 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/spark_cluster_private_key'
192.168.34.89 ansible_ssh_host=192.168.34.89 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/spark_cluster_private_key'
192.168.34.90 ansible_ssh_host=192.168.34.90 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/spark_cluster_private_key'
192.168.34.91 ansible_ssh_host=192.168.34.91 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/spark_cluster_private_key'

[zookeeper]
192.168.34.18
192.168.34.19
192.168.34.20

[spark_master]
192.168.34.88
192.168.34.89

[spark]
192.168.34.90
192.168.34.91
```

As you can see, host groups are defined for the nodes that make up a three-node (pre-existing) external Zookeeper ensemble, and the nodes that make up our Spark cluster are broken down into two host groups; the `spark_master` group containing the master nodes and the `spark` group containing the non-master (or worker) nodes. Those of you who have already used the [provision-spark.yml](../provision-spark.yml) playbook to deploy a Spark cluster using a static inventory file should be familiar with the structure of this inventory file.

In addition to the inventory file, we'll be using a local variables file to set the parameter values necessary to provision our secure Spark cluster. That local variables file looks something like this:

```
$ cat test-secure-local.yml
---
data_iface: eth0
api_iface: eth1
spark_data_dir: '/data'
secure_cluster: true
spark_admin_users: 'admin'
spark_modify_users: 'power_user'
spark_view_users: 'non_power_user,power_user'
spark_master_virtual_ip: 192.168.44.142
```
 
 As you can see, we've defined values containing lists of users who should be included in the `spark.admin`, `spark.modify`, and `spark.ui.view` ACLs using the `spark_admin_users`, `spark_modify_users`, and `spark_view_users` parameters. We have also specified a value for the `spark_master_virtual_ip` containing the IP address that the `keepalived` daemon deployed to the master nodes should manage (assigning that virtual IP address to the active master node).
 
With the external Zookeeper ensemble already provisioned, the command we use to provision a new, secure Spark cluster (where user authentication is performed locally by the Spark nodes) looks like this:

```
$ ./provision-spark.yml -i secure_local_multi_master_inventory -e "{ local_vars_file: 'test-secure-local.yml' }"
```

This command will provision a four-node multi-master Spark cluster (with two master nodes and two worker nodes), configure the master nodes to talk to each other through the external, three-node Zookeeper ensemble defined in our inventory file, and configure the nodes in the cluster to perform user authentication locally.

In addition, a shared secret will be generated that is used to restrict access to the cluster to only those nodes that have the shared secret, and the nodes of the cluster will be configured to use that shared secret. The nodes of the cluster will be configured to listen on the private (`data_iface`) network, the virtual IP defined by the `spark_master_virtual_ip ` value will be configured for the master nodes of the cluster (typically this virtual IP would be placed on the public, `api_iface` network when securing the network locally) and the `keepalived` daemon deployed to the master nodes will assign that virtual IP address to the active master node (in a multi-master deployment, there is only one active master node in the Spark cluster and the other master nodes remain on standby until the active node fails).

When the playbook run is complete, the resulting configuration will look something like this:

```
                           ┌──────────────────────┐                              
                           │   Spark Virtual IP   │                              
                           └──────────────────────┘                    Public    
                                       │                               Network   
 ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┼ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ 
                         ── ── ── ── ──└──────────────┐                Private   
                        │                             │                Network   
                        ▼                             ▼                          
             ┌─────────────────────┐       ┌─────────────────────┐               
             │   Spark Master #1   │       │   Spark Master #2   │               
             └─────────────────────┘       └─────────────────────┘               
                                                      │                          
                                       ┌──────────────┴──────────────┐           
                                       │                             │           
                                       ▼                             ▼           
                            ┌─────────────────────┐       ┌─────────────────────┐
                            │   Spark Worker #1   │       │   Spark Worker #2   │
                            └─────────────────────┘       └─────────────────────┘
```

In this example, the `Spark Virtual IP` has been bound to `Spark Master #2` (the active master node of our four-node Spark cluster) and this active master node is configured as a proxy for the underlying worker nodes (`Spark Worker #1` and `Spark Worker #2`). So a request received via the `Spark Virtual IP` will be redirected to the active master node's web UI, and it is through that web UI that users can access the underlying worker nodes.
 
### Deployment of a cluster secured via NGINX

In this example, we will provision a new secure cluster (where authentication is handled remotely by a pre-existing NGINX instance/cluster) using the [provision-spark.yml](../provision-spark.yml) playbook. To deploy a remotely secured cluster, we must define values for a few additional parameters (above and beyond those that are defined when deploying an unsecured Spark cluster using this same playbook); specifically, we need to define the values for the following parameters:

* **`secure_cluster`**: a flag indicating whether or not we should secure the cluster we are deploying this flag defaults to `false` (so, by default, the -spark_master_virtual_ip playbook deploys an unsecured Spark cluser); as such, this flag must be set to `true` to deploy a new, secure cluster
* **`spark_master_virtual_ip`**: the IP address of the vitual IP that should be bound to the active master node by the `keepalived` daemon; an IP address must be provided for this parameter or the role that secures the Spark cluster will fail to execute (and the playbook run will not be successful)
* **`reverse_proxy_url`**: the URL of the NGINX instance (or the virtual IP of the active node of a multi-node NGINX cluster) that should be configured as a reverse proxy for the Spark cluster; this instance/cluster will be used to control access to the underlying Spark cluster via all requests will be redirected through this URL

In addition to these two parameters, values can also be provided for any (or all) of the three parameters that are used to control user membership in the ACLs that are configured during the playbook (see the [Access Control Lists](#Access-Control-Lists) section of this document for more details). In this example we will setup three user accounts (one *admin* account, one *modify* account, and one *view* account) using the values defined in a local variables file for the three parameters described in that section of this document, but the details for this are not critical to the example.

The inventory file that we'll be using for our playbook run will be slightly different from the inventory shown in the previous example in that we will also be defining an `nginx` host group in our inventory file containing the hosts that make up a two-node NGINX cluster. The inventory file that we'll be using looks like this:

```
$ cat secure_nginx_multi_master_inventory
# assumes that there is already a two-node NGINX cluster running and a three-node
# Zookeeper ensemble running

192.168.34.250 ansible_ssh_host= 192.168.34.250 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/nginx_cluster_private_key'
192.168.34.251 ansible_ssh_host= 192.168.34.251 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/nginx_cluster_private_key'

192.168.34.18 ansible_ssh_host=192.168.34.18 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/zk_cluster_private_key'
192.168.34.19 ansible_ssh_host=192.168.34.19 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/zk_cluster_private_key'
192.168.34.20 ansible_ssh_host=192.168.34.20 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/zk_cluster_private_key'

192.168.34.88 ansible_ssh_host=192.168.34.88 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/spark_cluster_private_key'
192.168.34.89 ansible_ssh_host=192.168.34.89 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/spark_cluster_private_key'
192.168.34.90 ansible_ssh_host=192.168.34.90 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/spark_cluster_private_key'
192.168.34.91 ansible_ssh_host=192.168.34.91 ansible_ssh_port=22 ansible_ssh_user='cloud-user' ansible_ssh_private_key_file='keys/spark_cluster_private_key'

[nginx]
192.168.34.250
192.168.34.251

[zookeeper]
192.168.34.18
192.168.34.19
192.168.34.20

[spark_master]
192.168.34.88
192.168.34.89

[spark]
192.168.34.90
192.168.34.91
```

In addition to the inventory file, we'll be using a local variables file to set the parameter values necessary to provision our secure Spark cluster. That local variables file looks something like this:

```
$ cat test-secure-nginx.yml
---
data_iface: eth0
api_iface: eth1
spark_data_dir: '/data'
secure_cluster: true
spark_admin_users: 'admin'
spark_modify_users: 'power_user'
spark_view_users: 'non_power_user,power_user'
spark_master_virtual_ip: 192.168.34.142
reverse_proxy_url: http://192.168.44.242
```

With the NGINX instances and our external Zookeeper ensemble already provisioned, the command we use to provision a new, secure Spark cluster (where user authentication is performed by the external, pre-existing NGINX cluster) looks like this:

```
$ ./provision-spark.yml -i secure_nginx_multi_master_inventory -e "{ local_vars_file: 'test-secure-nginx.yml' }"
```

This command will provision a four-node multi-master Spark cluster (with two master nodes and two worker nodes), configure the master nodes to talk to each other through the external, three-node Zookeeper ensemble defined in our inventory file, and configure the NGINX cluster from our inventory file as a reverse proxy for the Spark cluster.

In addition, a shared secret will be generated that is used to restrict access to the cluster to only those nodes that have the shared secret, and the nodes of the cluster will be configured to use that shared secret. The nodes of the cluster will be configured to listen on the private (`data_iface`) network, the a virtual IP defined by the `spark_master_virtual_ip ` parameter will be configured for the master nodes of the cluster (typically this virtual IP would be placed on the private, `data_iface` network when securing the cluster using an external NGINX instance or cluster) and the `keepalived` daemon deployed to the master nodes will assign that virtual IP address to the active master node (in a multi-master deployment, there is only one active master node in the Spark cluster and the other master nodes remain on standby until the active node fails).

When the playbook run is complete, the resulting configuration will look something like this:

```
                           ┌──────────────────────┐                              
                           │   NGINX Virtual IP   │                              
                           └──────────────────────┘                              
                                       │                                         
                       ┌───────────────┴  ──  ──  ──  ┐                          
                       ▼                              ▼                Public    
           ┌───────────────────────┐      ┌───────────────────────┐    Network   
 ─ ─ ─ ─ ─ ┤   NGINX Instance #1   ├ ─ ─ ─│   NGINX Instance #2   │─ ─ ─ ─ ─ ─ ─ 
           └───────────────────────┘      └───────────────────────┘    Private   
                       │                              │                Network   
                       │                              │                          
                       └───────────────┬─  ──  ──  ──                            
                                       │                                         
                                       │                                         
                                       ▼                                         
                           ┌──────────────────────┐                              
                           │   Spark Virtual IP   │                              
                           └──────────────────────┘                              
                                       │                                         
                        ┌  ──  ──  ──  ┴──────────────┐                          
                        ▼                             ▼                          
             ┌─────────────────────┐       ┌─────────────────────┐               
             │   Spark Master #1   │       │   Spark Master #2   │               
             └─────────────────────┘       └─────────────────────┘               
                                                      │                          
                                       ┌──────────────┴──────────────┐           
                                       ▼                             ▼           
                            ┌─────────────────────┐       ┌─────────────────────┐
                            │   Spark Worker #1   │       │   Spark Worker #2   │
                            └─────────────────────┘       └─────────────────────┘
```

In this example, the `NGINX Virtual IP` has been bound to `NGINX Instance #1` (the active node of our two-node NGINX cluster) and the second node of our two-node NGINX cluster (`NGINX Instance #2`) is on standby, ready to take over if the first node fails. Similarly, the `Spark Master #2` node is the active master node of our four node Spark cluster, and this active master node is configured as a proxy for the underlying worker nodes (`Spark Worker #1` and `Spark Worker #2`). So a request received via the `NGINX Virtual IP` will be redirected to the active master node's web UI, and it is through that web UI that users can access the underlying worker nodes.

### Securing an existing cluster locally

The process of securing an existing cluster locally is very similar to the process of deploying a locally secured cluster which was described in [this section](#Deployment-of-a-cluster-secured-locally). In fact, the command (assuming we're reusing the inventory and local variables file shown in that section) even looks quite similar:

```
$ ./secure-spark.yml -i secure_local_multi_master_inventory -e "{ local_vars_file: 'test-secure-local.yml' }"
```

This playbook will connect to all of the nodes that make up the Spark cluster and configure them, based on their role in the cluster, such that:

* They are listening for connections on the private, or `data_iface` network
* They use a shared key (which is generated at the start of the playbook run) to secure the cluster (only nodes with the pre-defined shared key are allowed to join the cluster) 
* The active master node acts as a reverse proxy for the underlying worker nodes, providing access to those nodes through the active master node's web UI
* The active master node's web UI is accessible through a virtual IP (set using the `spark_master_virtual_ip` parameter) that is maintained by the `keepalived` daemon deployed to each of the master nodes; typically this virtual IP would be placed on the public (or `api_iface`) network to allow for client connections to the underlying cluster
* the `iptables` package is installed on the master nodes and used to configure port forwarding from the virtual IP assigned to the active master to the IP address of the (private) interface that the active master is actually listening on
* Basic Authentication is used to restrict access to the active master node's web UI to only those users who have been provided access
* Access Control Lists are setup on the nodes that make up the cluster based on the user groups that are passed into the playbook run using the `spark_admin_users`, `spark_modify_users`, and `spark_view_users` parameters

It should be noted that while we are re-using the local variables file that was used in the section of this document that describes how to [deploy a locally secured cluster](#Deployment-of-a-cluster-secured locally), the value for the `secure_cluster` flag that is defined in that local variables file is irrelavent. This is because the [secure-spark.yml](../secure-spark.yml) playbook actually sets the value of this flag to `true` at the start of the playbook run.

In practice, this means that while the [secure-spark.yml](../secure-spark.yml) playbook can be used to secure an existing (non-secure) cluster locally or to switch the mechanism used to secure an existing cluster from authentication via NGINX to local authentication (see the [next section](#Securing-an-existing-cluster-via-NGINX) for more details on this process), there is no support provided by this playbook for taking a secured Spark cluster deployment and migrating it back to an unsecured Spark cluster deployment. If that process proves to be necessary for some users, we will either have to add a new playbook to this repository or modify the existing [secure-spark.yml](../secure-spark.yml) playbook so that it does not set the `secure_cluster` parameter to `true` by default at the start of the playbook run.

### Securing an existing cluster via NGINX

As was the case with securing an existing cluster locally, the process of securing an existing cluster via NGINX is very similar to the process of deploying a cluster that is secured via NGINX which was described in [this section](#Deployment-of-a-cluster-secured-via-NGINX). In fact, the command (assuming we're reusing the inventory and local variables file shown in that section) even looks quite similar:

```
$ ./secure-spark.yml -i secure_nginx_multi_master_inventory -e "{ local_vars_file: 'test-secure-nginx.yml' }"
```

This playbook will connect to all of the nodes that make up the Spark cluster and configure them, based on their role in the cluster, such that:

* They are listening for connections on the private, or `data_iface` network
* They use a shared key (which is generated at the start of the playbook run) to secure the cluster (only nodes with the pre-defined shared key are allowed to join the cluster)
* The active master node acts as a reverse proxy for the underlying worker nodes, providing access to those nodes through the active master node's web UI
* The NGINX instance/cluster is configured as a reverse proxy for the underlying Spark cluster, and requests for the root-level URI of the NGINX instance/cluster are redirected to the virtual IP address associated with the active master node's web UI (this IP address is set using the `spark_master_virtual_ip` parameter, and is maintained by the `keepalived` daemon deployed to each of the master nodes); in this configuration, the NGINX instance/cluster is typically placed on the public (or `api_iface`) network and the virtual IP address associated with the active master node in the Spark cluster is typically placed onto the private (or `data_iface`) network to ensure that direct access to that IP address is not allowed
* the `iptables` package is installed on the master nodes and used to configure port forwarding from the virtual IP assigned to the active master to the IP address of the (private) interface that the active master is actually listening on
* Basic Authentication is used to restrict access to the NGINX instance/cluster to only those users who have been provided access, and the username of any users who successfully authenticate with the NGINX instance/cluster is passed along to the underlying active master node in the Spark cluster as part of the forwarded HTTP request
* Access Control Lists are setup on the nodes that make up the cluster based on the user groups that are passed into the playbook run using the `spark_admin_users`, `spark_modify_users`, and `spark_view_users` parameters; as was mentioned previously, care should be taken to ensure that all of the users in these lists are provided access to the associated NGINX instance/cluster

It should be noted that while we are re-using the local variables file that was used in the section of this document that describes how to [deploy a cluster secured via NGINX](#Deployment-of-a-cluster-secured-via-NGINX), the value for the `secure_cluster` flag that is defined in that local variables file is irrelavent. This is because the [secure-spark.yml](../secure-spark.yml) playbook actually sets the value of this flag to `true` at the start of the playbook run.

In practice, this means that while the [secure-spark.yml](../secure-spark.yml) playbook can be used to secure an existing (non-secure) cluster locally or to switch the mechanism used to secure an existing cluster from local authentication to authentication via NGINX (see the [next section](#Securing-an-existing-cluster-locally) for more details on this process), there is no support provided by this playbook for taking a secured Spark cluster deployment and migrating it back to an unsecured Spark cluster deployment. If that process proves to be necessary for some users, we will either have to add a new playbook to this repository or modify the existing [secure-spark.yml](../secure-spark.yml) playbook so that it does not set the `secure_cluster` parameter to `true` by default at the start of the playbook run.
