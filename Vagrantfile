# (c) 2017 DataNexus Inc.  All Rights Reserved
# -*- mode: ruby -*-
# vi: set ft=ruby :

require 'optparse'
require 'resolv'

# monkey-patch that is used to leave unrecognized options in the ARGV
# list so that they can be processed by underlying vagrant command
class OptionParser
  # Like order!, but leave any unrecognized --switches alone
  def order_recognized!(args)
    extra_opts = []
    begin
      order!(args) { |a| extra_opts << a }
    rescue OptionParser::InvalidOption => e
      extra_opts << e.args[0]
      retry
    end
    args[0, 0] = extra_opts
  end
end

# a function that is used to parse Ansible (static) inventory files and
# return a list of the node addresses contained in the file
def addr_list_from_inventory_file(inventory_file)
  first_field_list = []
  File.open(inventory_file, 'r') do |f|
    f.each_line do |line|
      # grab the first field from each line
      first_field_list << line.gsub(/\s+/, ' ').strip.split(" ")[0]
    end
  end
  # return the entries that look like IP addresses (skipping the rest)
  # and only return the unique values in the resulting list
  first_field_list.select { |addr| (addr =~ Resolv::IPv4::Regex) }.uniq
end

# and define a function that we'll use during the provisioning process
# to reduce code duplication (since we provision clusters in two passes
# that are essentially identical)
def setup_ansible_config(ansible, provisioned_nodes, options)
  # set the limit to 'all' in order to provision all of machines on the
  # list in a single playbook run
  ansible.limit = "all"
  ansible.playbook = "site.yml"
  ansible.groups = {
    spark: provisioned_nodes
  }
  ansible.extra_vars = {
    proxy_env: {
      http_proxy: options[:proxy],
      no_proxy: options[:no_proxy],
      proxy_username: options[:proxy_username],
      proxy_password: options[:proxy_password]
    },
    host_inventory: provisioned_nodes,
    reset_proxy_settings: options[:reset_proxy_settings],
    yum_repo_url: options[:yum_repo_url],
    cloud: "vagrant",
    data_iface: "eth1",
    api_iface: "eth2",
    zookeeper_inventory_file: options[:inventory_file]
  }

  # if defined, set the 'extra_vars[:spark_url]' value to the value that was passed in on
  # the command-line (eg. "https://10.0.2.2/spark-2.1.0-bin-without-hadoop.tgz")
  if options[:spark_url]
    ansible.extra_vars[:spark_url] = options[:spark_url]
  end

  # if defined, set the 'extra_vars[:spark_version]' value to the value that was passed in on
  # the command-line (eg. "2.1.0")
  if options[:spark_version]
    ansible.extra_vars[:spark_version] = options[:spark_version]
  end

  # if defined, set the 'extra_vars[:spark_dir]' value to the value that was passed in on
  # the command-line (eg. "/opt/apache-spark")
  if options[:spark_dir]
    ansible.extra_vars[:spark_dir] = options[:spark_dir]
  end

  # if defined, set the 'extra_vars[:spark_data_dir]' value to the value that was passed in on
  # the command-line (eg. "/var/lib")
  if options[:spark_data_dir]
    ansible.extra_vars[:spark_data_dir] = options[:spark_data_dir]
  end

  # if defined, set the 'extra_vars[:local_vars_file]' value to the value that was passed in
  # on the command-line (eg. "/tmp/local-vars-file.yml")
  if options[:local_vars_file]
    ansible.extra_vars[:local_vars_file] = options[:local_vars_file]
  end

  # if defined, set the 'extra_vars[:spark_master_nodes]' value to the value that was passed in on
  # the command-line (eg. "127.0.0.1")
  if options[:spark_master_array].size > 0
    ansible.extra_vars[:spark_master_nodes] = options[:spark_master_array]
  end
end

# initialize a few values
options = {}
VALID_ZK_ENSEMBLE_SIZES = [3, 5, 7]
# vagrant commands that include these commands can be run without specifying
# any IP addresses
no_ip_commands = ['version', 'global-status', '--help', '-h']
# vagrant commands that only work for a single IP address
single_ip_commands = ['status', 'ssh']
# vagrant command arguments that indicate we are provisioning a cluster (if multiple
# nodes are supplied via the `--spark-list` flag)
provisioning_command_args = ['up', 'provision']
no_zk_required_command_args = ['destroy']
not_provisioning_flag = ['--no-provision']

optparse = OptionParser.new do |opts|
  opts.banner    = "Usage: #{opts.program_name} [options]"
  opts.separator "Options"

  options[:spark_list] = nil
  opts.on( '-s', '--spark-list A1,A2[,...]', 'Spark address list' ) do |spark_list|
    # while parsing, trim an '=' prefix character off the front of the string if it exists
    # (would occur if the value was passed using an option flag like '-s=192.168.1.1')
    options[:spark_list] = spark_list.gsub(/^=/,'')
  end

  options[:inventory_file] = nil
  opts.on( '-i', '--inventory-file FILE', 'Zookeeper (Ansible) inventory file' ) do |inventory_file|
    # while parsing, trim an '=' prefix character off the front of the string if it exists
    # (would occur if the value was passed using an option flag like '-i=/tmp/zookeeper_inventory')
    options[:inventory_file] = inventory_file.gsub(/^=/,'')
  end

  options[:spark_dir] = nil
  opts.on( '-p', '--path PATH', 'Path where the distribution should be installed' ) do |spark_dir|
    # while parsing, trim an '=' prefix character off the front of the string if it exists
    # (would occur if the value was passed using an option flag like '-h=/opt/apache-spark')
    options[:spark_dir] = spark_dir.gsub(/^=/,'')
  end

  options[:spark_data_dir] = nil
  opts.on( '-d', '--data PATH', 'Path where Spark will store its data' ) do |spark_data_dir|
    # while parsing, trim an '=' prefix character off the front of the string if it exists
    # (would occur if the value was passed using an option flag like '-d=/data')
    options[:spark_data_dir] = spark_data_dir.gsub(/^=/,'')
  end

  options[:spark_url] = nil
  opts.on( '-u', '--url URL', 'URL the distribution should be downloaded from' ) do |spark_url|
    # while parsing, trim an '=' prefix character off the front of the string if it exists
    # (would occur if the value was passed using an option flag like '-u=http://localhost/tmp.tgz')
    options[:spark_url] = spark_url.gsub(/^=/,'')
  end

  options[:spark_version] = nil
  opts.on( '-v', '--version VERSION', 'Spark version to install' ) do |spark_version|
    # while parsing, trim an '=' prefix character off the front of the string if it exists
    # (would occur if the value was passed using an option flag like '-v=3.10')
    options[:spark_version] = spark_version.gsub(/^=/,'')
  end

  options[:spark_master_nodes] = nil
  opts.on( '-m', '--master-nodes A1,A2[,...]', 'Spark master nodes address list' ) do |spark_master_nodes|
    # while parsing, trim an '=' prefix character off the front of the string if it exists
    # (would occur if the value was passed using an option flag like '-m=127.0.01')
    options[:spark_master_nodes] = spark_master_nodes.gsub(/^=/,'')
  end

  options[:yum_repo_url] = nil
  opts.on( '-y', '--yum-url URL', 'Local yum repository URL' ) do |yum_repo_url|
    # while parsing, trim an '=' prefix character off the front of the string if it exists
    # (would occur if the value was passed using an option flag like '-y=http://192.168.1.128/centos')
    options[:yum_repo_url] = yum_repo_url.gsub(/^=/,'')
  end

  options[:local_vars_file] = nil
  opts.on( '-f', '--local-vars-file FILE', 'Local variables file' ) do |local_vars_file|
    # while parsing, trim an '=' prefix character off the front of the string if it exists
    # (would occur if the value was passed using an option flag like '-f=/tmp/local-vars-file.yml')
    options[:local_vars_file] = local_vars_file.gsub(/^=/,'')
  end

  options[:reset_proxy_settings] = false
  opts.on( '-c', '--clear-proxy-settings', 'Clear existing proxy settings if no proxy is set' ) do |reset_proxy_settings|
    options[:reset_proxy_settings] = true
  end

  opts.on_tail( '-h', '--help', 'Display this screen' ) do
    print opts
    exit
  end

end

begin
  optparse.order_recognized!(ARGV)
rescue SystemExit
  exit
rescue Exception => e
  print "ERROR: could not parse command (#{e.message})\n"
  print optparse
  exit 1
end

# check remaining arguments to see if the command requires
# an IP address (or not)
ip_required = (ARGV & no_ip_commands).empty?
# check the remaining arguments to see if we're provisioning or not
provisioning_command = !((ARGV & provisioning_command_args).empty?) && (ARGV & not_provisioning_flag).empty?
# and to see if multiple IP addresses are supported (or not) for the
# command being invoked
single_ip_command = !((ARGV & single_ip_commands).empty?)
# and to see if a zookeeper inventory must also be provided
no_zk_required_command = !(ARGV & no_zk_required_command_args).empty?

if options[:spark_url] && !(options[:spark_url] =~ URI::regexp)
  print "ERROR; input Spark URL '#{options[:spark_url]}' is not a valid URL\n"
  exit 3
end

# if a yum repository address was passed in, check and make sure it's a valid URL
if options[:yum_repo_url] && !(options[:yum_repo_url] =~ URI::regexp)
  print "ERROR; input yum repository URL '#{options[:yum_repo_url]}' is not a valid URL\n"
  exit 6
end

if options[:inventory_file] && !File.file?(options[:inventory_file])
  print "ERROR; the if a zookeeper list is defined, a zookeeper inventory file must also be provided\n"
  exit 2
end

# if a local variables file was passed in, check and make sure it's a valid filename
if options[:local_vars_file] && !File.file?(options[:local_vars_file])
  print "ERROR; input local variables file '#{options[:local_vars_file]}' is not a local file\n"
  exit 3
end

# if we're provisioning, then the `--spark-list` flag must be provided and either contain
# a single node (for single-node deployments) or multiple nodes in a comma-separated list
# (for multi-node deployments) that define a valid spark cluster
spark_addr_array = []
spark_master_array = []
spark_non_master_array = []
if provisioning_command || ip_required
  if !options[:spark_list]
    print "ERROR; IP address must be supplied (using the `-s, --spark-list` flag) for this vagrant command\n"
    exit 1
  else
    spark_addr_array = options[:spark_list].split(',').map { |elem| elem.strip }.reject { |elem| elem.empty? }
    if spark_addr_array.size == 1
      if !(spark_addr_array[0] =~ Resolv::IPv4::Regex)
        print "ERROR; input Spark IP address #{spark_addr_array[0]} is not a valid IP address\n"
        exit 2
      end
    elsif !single_ip_command
      # check the input `spark_addr_array` to ensure that all of the values passed in are
      # legal IP addresses
      not_ip_addr_list = spark_addr_array.select { |addr| !(addr =~ Resolv::IPv4::Regex) }
      if not_ip_addr_list.size > 0
        # if some of the values are not valid IP addresses, print an error and exit
        if not_ip_addr_list.size == 1
          print "ERROR; input Spark IP address #{not_ip_addr_list} is not a valid IP address\n"
          exit 2
        else
          print "ERROR; input Spark IP addresses #{not_ip_addr_list} are not valid IP addresses\n"
          exit 2
        end
      end
      # if we're provisioning a cluster, then a list of master nodes is needed
      if provisioning_command && spark_addr_array.size > 1 && !options[:spark_master_nodes]
        print "ERROR; List of master node addresses must be supplied (using the `-m, --master-nodes` flag) for this vagrant command\n"
        exit 1
      elsif provisioning_command
        spark_master_array = options[:spark_master_nodes].split(',').map { |elem| elem.strip }.reject { |elem| elem.empty? }
        if spark_master_array.size == 1
          if !(spark_master_array[0] =~ Resolv::IPv4::Regex)
            print "ERROR; input Spark master address #{spark_master_array[0]} is not a valid IP address\n"
            exit 2
          end
        else
          # check the input `spark_master_array` to ensure that all of the values passed in are
          # legal IP addresses
          not_ip_addr_list = spark_master_array.select { |addr| !(addr =~ Resolv::IPv4::Regex) }
          if not_ip_addr_list.size > 0
            # if some of the values are not valid IP addresses, print an error and exit
            if not_ip_addr_list.size == 1
              print "ERROR; input Spark master address #{not_ip_addr_list} is not a valid IP address\n"
              exit 2
            else
              print "ERROR; input Spark master addresses #{not_ip_addr_list} are not valid IP addresses\n"
              exit 2
            end
          end
        end
        # make sure that the master nodes that are passed in are part of the list of Spark
        # node addresses that were passed in
        not_in_spark_array = spark_master_array - spark_addr_array
        if not_in_spark_array.size == 1
          print "ERROR; input Spark master address #{not_in_spark_array} is not in the list of Spark addresses\n"
          exit 2
        elsif not_in_spark_array.size > 1
          print "ERROR; input Spark master addresses #{not_in_spark_array} are not in the list of Spark addresses\n"
          exit 2
        end
        # when provisioning a multi-master Spark cluster, we **must** have an associated zookeeper
        # ensemble consisting of an odd number of nodes greater than three, but less than seven
        # (any other topology is not supported, so an error is thrown)
        if spark_master_array.size > 1 && !no_zk_required_command
          if !options[:inventory_file]
            print "ERROR; A zookeeper inventory file must be supplied (using the `-i, --inventory-file` flag)\n"
            print "       containing the (static) inventory file for an existing Zookeeper ensemble when\n"
            print "       provisioning a multi-master Spark cluster\n"
            exit 1
          else
            # parse the inventory file that was passed in and retrieve the list of host addresses from it
            zookeeper_addr_array = addr_list_from_inventory_file(options[:inventory_file])
            # and check to make sure that an appropriate number of zookeeper addresses were
            # found in the inventory file (the size of the ensemble should be an odd number
            # between three and seven)
            if !(VALID_ZK_ENSEMBLE_SIZES.include?(zookeeper_addr_array.size))
              print "ERROR; only a zookeeper cluster with an odd number of elements between three and\n"
              print "       seven is supported for multi-master Spark deployments; the defined cluster\n"
              print "       #{zookeeper_addr_array} contains #{zookeeper_addr_array.size} elements\n"
              exit 5
            end
            # finally, we need to make sure that the machines we're deploying Spark to are not the same
            # machines that make up our zookeeper ensemble (the zookeeper ensemble must be on a separate
            # set of machines from the Spark cluster)
            same_addr_list = zookeeper_addr_array & spark_addr_array
            if same_addr_list.size > 0
              print "ERROR; the Spark cluster cannot be deployed to the same machines that make up\n"
              print "       the zookeeper ensemble; requested clusters overlap for the machines at\n"
              print "       #{same_addr_list}\n"
              exit 7
            end
          end
        end
      end
    end
  end
end

# if we get to here and a list of master nodes wasn't provided, then we're
# performing a single-node deployment and all of the nodes are "non-master"
# nodes for the purposes of the playbook that will deploy Spark to
# that node
if spark_non_master_array.size == 0
  spark_non_master_array = spark_addr_array
end

# and determine which of the input nodes are *not* master nodes
# (these are the worker nodes)
spark_non_master_array = spark_addr_array - spark_master_array

# and set the value for options[:spark_master_array] to the spark_master_array
# (we will use this value when provisioning our master and non-master nodes, below)
options[:spark_master_array] = spark_master_array

# All Vagrant configuration is done below. The "2" in Vagrant.configure
# configures the configuration version (we support older styles for
# backwards compatibility). Please don't change it unless you know what
# you're doing.
if spark_addr_array.size > 0
  Vagrant.configure("2") do |config|
    options[:proxy] = ENV['http_proxy'] || ""
    options[:no_proxy] = ENV['no_proxy'] || ""
    options[:proxy_username] = ENV['proxy_username'] || ""
    options[:proxy_password] = ENV['proxy_password'] || ""
    if Vagrant.has_plugin?("vagrant-proxyconf")
      if options[:proxy] != ""
        config.proxy.http               = options[:proxy]
        config.proxy.no_proxy           = "localhost,127.0.0.1"
        config.vm.box_download_insecure = true
        config.vm.box_check_update      = false
      end
      if options[:no_proxy] != ""
        config.proxy.no_proxy           = options[:no_proxy]
      end
      if options[:proxy_username] != ""
        config.proxy.proxy_username     = options[:proxy_username]
      end
      if options[:proxy_password] != ""
        config.proxy.proxy_password     = options[:proxy_password]
      end
    end
        # Every Vagrant development environment requires a box. You can search for
    # boxes at https://atlas.hashicorp.com/search.
    config.vm.box = "centos/7"
    config.vm.box_check_update = false

    # loop through all of the addresses in the `spark_addr_array` and, if we're
    # creating VMs, create a VM for each machine; if we're just provisioning the
    # VMs using an ansible playbook, then wait until the last VM in the loop and
    # trigger the playbook runs for all of the nodes simultaneously using the
    # `site.yml` playbook
    spark_addr_array.each do |machine_addr|
      config.vm.define machine_addr do |machine|
        # Create a two private networks, which each allow host-only access to the machine
        # using a specific IP.
        machine.vm.network "private_network", ip: machine_addr
        split_addr = machine_addr.split('.')
        api_addr = (split_addr[0..1] + [(split_addr[2].to_i + 10).to_s] + [split_addr[3]]).join('.')
        machine.vm.network "private_network", ip: api_addr
        # set the memory for this instance
        machine.vm.provider "virtualbox" do |vb|
          # Customize the amount of memory on the VM:
          # vb.memory = "8192"
          vb.memory = "2048"
       end
        # if it's the last node in the list if input addresses, then provision
        # all of the nodes simultaneously (if the `--no-provision` flag was not
        # set, of course)
        if machine_addr == spark_addr_array[-1]
          # now, use the playbook in the `site.yml' file to provision our
          # nodes with Spark (and configure them as a cluster if there
          # is more than one node); this provisioning is done in two stages,
          # first provision the master nodes (if any), then the non-master nodes
          if spark_master_array.size > 0
            machine.vm.provision "ansible" do |ansible|
              setup_ansible_config(ansible, spark_master_array, options)
            end
          end
          if spark_non_master_array.size > 0
            machine.vm.provision "ansible" do |ansible|
              setup_ansible_config(ansible, spark_non_master_array, options)
            end
          end
        end
      end
    end
  end
end
