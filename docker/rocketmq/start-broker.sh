#!/bin/sh
set -eu
cat > /tmp/localjoy-broker.conf <<EOF
brokerClusterName=localjoy-cluster
brokerName=localjoy-broker
brokerId=0
brokerRole=ASYNC_MASTER
flushDiskType=ASYNC_FLUSH
namesrvAddr=namesrv:9876
brokerIP1=${ROCKETMQ_BROKER_IP:-host.docker.internal}
listenPort=10911
autoCreateTopicEnable=true
autoCreateSubscriptionGroup=true
storePathRootDir=/home/rocketmq/store
storePathCommitLog=/home/rocketmq/store/commitlog
fileReservedTime=48
EOF
exec sh mqbroker -c /tmp/localjoy-broker.conf
