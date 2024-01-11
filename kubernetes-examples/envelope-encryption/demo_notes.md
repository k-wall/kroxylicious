
# Show Kafka, Vault, Kroxy

kubectl get kafka -n kafka
kubectl get pods -n vault

kubectl get pods -n kroxylicious
kubectl get cm -n kroxylicious kroxylicious-config

# Nodes

kaf  -b minikube:30192 nodes list
kubectl -n kafka run nodes -ti --image=quay.io/kroxylicious/kaf --rm=true --restart=Never -- kaf -b my-cluster-kafka-bootstrap:9092 nodes list


# Key setup

http://192.168.64.45:30365/

kubectl exec -n vault vault-0 -- vault write -f transit/keys/trades

kubectl exec -n vault vault-0 -- vault read transit/keys/trades

kubectl exec -n vault vault-0 -- vault list transit/keys

# Topic

kaf  -b minikube:30192 topic create trade

# Publish/consume

echo "ibm: 99" | kaf -b minikube:30192 produce trades

kubectl -n kafka run consumer -ti --image=quay.io/kroxylicious/kaf --rm=true --restart=Never -- kaf consume trades -b my-cluster-kafka-bootstrap:9092

kaf -b minikube:30192 consume trades


kaf -b minikube:30192 consume trades


That's the set-up done.

Now let's produce a record via the proxy.  We'll then confirm that the record is encrypted by consuming it *directly*
on the Kafka cluster.  Finally, we'll consume it via the proxy showing that it is the plain-text that is received.

These steps use [kaf](https://github.com/birdayz/kaf) to produce and consume messages, but you can use your preferred
Kafka tooling if you like.

6. Publish a record via the proxy.
    ```shell { prompt="Time to start producing and consuming records.  First let's produce a record via the proxy."
   echo "ibm: 99" | kaf -b minikube:30192 produce trades
   ```
6. Now we verify that the record is truly encrypted on the Kafka Cluster by consuming it directly from the Kafka Cluster.
   ```shell { prompt="To show that the record is encrypted on the cluster, let's consume it directly from it. We'll see unintelligible bytes rather than the plain-text record." }
   kubectl -n kafka run consumer -ti --image=quay.io/kroxylicious/kaf --rm=true --restart=Never -- kaf consume trades -b my-cluster-kafka-bootstrap:9092
   ```
6. Finally, we consume the same record via the proxy.  We'll get back our original record.
   ```shell { prompt="Now let's consume the same record via the proxy.  This time we'll see the plain-text of the record as Kroxylicious will have decrypted it." }
   kaf -b minikube:30192 consume trades
   ```
6. Additionally, we can view metrics using `curl minikube:30090/metrics` which will expose some counters exposing the total number of vault operations.
