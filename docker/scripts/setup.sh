#!/bin/bash

# ensure the environment is available in cron jobs
printenv | grep -v "no_proxy" >> /etc/environment

sh -xc "echo ${HOST_NAME} > /etc/hostname; hostname -F /etc/hostname"
sh -xc "sed -i -e '/^127.0.1.1/d' /etc/hosts; echo 127.0.1.1 ${HOST_NAME}.${DOMAIN} ${HOST_NAME} >> /etc/hosts"

# configure sumo
cp $MILL_HOME/sumo.conf /opt/SumoCollector/config/user.properties

# start sumo
service collector start
