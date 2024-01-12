
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

kaf  -b minikube:30192 topic create trades

# Publish/consume

echo "ibm: 99" | kaf -b minikube:30192 produce trades

kubectl -n kafka run consumer -ti --image=quay.io/kroxylicious/kaf --rm=true --restart=Never -- kaf consume trades -b my-cluster-kafka-bootstrap:9092

kaf -b minikube:30192 consume trades


kaf -b minikube:30192 consume trades


