cd /proxy
# The `velocity_secret` environment variable is added by Puffin when the container is created.
echo $velocity_secret > /proxy/forwarding.secret
java -jar proxy.jar