
# Overview

## Objective

The purpose of this fork of the [HentaiAtHome client](https://e-hentai.org/hentaiathome.php) is to allow it to run behind an off the shelf web server (currently nginx) and only handle the client functionality. This web server is then responsible for protocol negotiation and cached file service, based entirely on directions from the H@H client.

Among other things, this can add the following features for "free":
- Support for newer protocol features HTTP2, HSTS, OCSP
- proper etag, if-modified-since and range support

This along with GraalVM native-image might also mean lower CPU and memory usage, especially if you were already running a webserver for other purposes.

## Architecture

This is currently designed as a linux docker image with a volume mount as shared storage with the host. The host monitors a named pipe to know when the certificate is updated. The webserver serves the HTTP connections and forward all requests to the H@H client instance. If the file is cached, the the client returns a 0 byte 200 reply, with a special header indicating the real location of the content for the webserver to handle directly.

## Limitations

Currently enabling all these features implies disabling H@H client controlled bandwidth limiting, connection limits and flood control. However:
- Bandwidth limiting is probably better handled by Linux's QoS capabilities
- Flood control isn't inherently impossible with the above architecture. It could be re-added fairly easily. But:
- Nginx can be configured to handle floor control and connection limits, so why bother.

# Build

## Build instructions

A pre-built image is available at from `registry.gitlab.com/gruntledw/hentaiathome:latest`. So feel free to skip this section.

The pre-requisites for building and running are separate. You don't need to build on the same system you're targeting (though a similar cpu architecture may be required)

Pre-requisite, GraalVM + native-image. You can [follow the instructions](https://www.graalvm.org/java/quickstart/) up to step 4 (set the environment variables), then skip to the native-image section.

Docker is also needed if you want to build a docker image.

To actually build:
```bash
./make.sh && ./makejar.sh && ./make-native-image.sh && docker build -t hentai-at-home:dev .
```

Depending on what you want the build artifacts will either be:
- jar: `build/HentaiAtHome.jar`
- native: `build/HentaiAtHome`
- docker image: `hentai-at-home:dev`

If you need to transfer the docker image somewhere you can easily do it with:
```bash
docker save hentai-at-home:dev | ssh user@host docker load
```

Make sure to replace `registry.gitlab.com/gruntledw/hentaiathome:latest` by `hentai-at-home:dev` in the install instructions bellow.

# Install

## Install Pre-requisites

[Install docker](https://docs.docker.com/engine/install/)


Install the nginx software. On Ubuntu or Debian:
```bash
apt-get install nginx-light
```
You don't need to configure it yet.


If you have a firewall, make sure it's configured to allow incoming connections on the correct port.
If you're using firewalld the commands will look something like:
```bash
firewall-cmd --permanent --zone=public  --add-service=https
firewall-cmd --reload
```

Decide where you want to keep your HentaiAtHome data. I recommend giving it its own volume: 1) There's no risk either it or other services on the system end up using excess amounts of space; 2) You can add mount options like noatime,noexec,nodev,nosuid to make it more secure.
In the example configuration bellow, we assume we want everything stored under `/vol`. It can be in a more regular location like `/home` or `/var/lib`. Just make sure to replace the paths correctly.

## Install Instructions

To not need to edit commands too much we will occasionally set some environment variables. **If you log out, make sure to set them again** before continuing to copy and paste commands. Otherwise some commands may **destroy your system**.

```bash
HAH_HOME=/vol/hah

# Create a user so that the H@H client can run isolated.
useradd -d "$HAH_HOME" -m -r -s /bin/false hah

# Find out the numeric UID and GID of the hah user and hah group that were created.
# You should be able to do this by running:
stat /vol/hah

# or just set them:
HAH_UID=$(stat --printf="%u" "$HAH_HOME")
HAH_GID=$(stat --printf="%g" "$HAH_HOME")
```

Run the H@H client once, to perform initial configuration. This will ask you for your [client ID and key](https://e-hentai.org/hentaiathome.php) (click on the client). After, it will download your configuration and then fail connectivity tests. This is normal since it isn't set up yet. Hit Ctrl-C to exit the client.
```bash
docker run -it --rm -v "$HAH_HOME":/home/hah -u $HAH_UID:$HAH_GID registry.gitlab.com/gruntledw/hentaiathome:latest
```

Also save your password in `/root/hah-pass` and make sure to `chmod 0600 /root/hah-pass`

```bash
# Add keysync.pipe that will be used later to sync the H@H certificate from the docker image to outside
mkfifo "$HAH_HOME"/data/keysync.pipe
chgrp hah "$HAH_HOME"/data/keysync.pipe
chmod 0660 "$HAH_HOME"/data/keysync.pipe

# Harden the directory structure
chmod 0771 "$HAH_HOME"
chmod 0770 "$HAH_HOME"/{*,.??*} # A no such file or directory on .??* is fine
find "$HAH_HOME"/cache -type d -print0 | xargs -r0 chmod 0711
# This line assumes your real webserver is running with group www-data, if it's not put the correct group instead
chgrp www-data "$HAH_HOME"/cache
chmod 0710 "$HAH_HOME"/cache

# Setup nginx
cat <<END >> /etc/nginx/sites-available/hah
# Configuration for connections to hah backend
upstream hah-backend {
        server 127.0.0.1:1080;
        keepalive 16;
}

# Actual site configuration
server {
        # SSL configuration
        listen 443 ssl http2 default_server;
        server_name _;
        # Don't want this to be the default site? Use this instead:
        #listen 443 ssl http2;
        #server_name *.hath.network;

        # This SSL configuration is mostly from the mozilla SSL configuration generator
        #   https://ssl-config.mozilla.org/#server=nginx&version=1.17.7&config=old&openssl=1.1.1k&guideline=5.6
        # MAKE SURE to choose "Old" and add @SECLEVEL=0 to ssl_ciphers otherwise your quality/trust will be trash.

        ssl_certificate /etc/nginx/cert/hah.pem;
        ssl_certificate_key /etc/nginx/cert/private/hah.key;
        ssl_session_timeout 1d;
        ssl_session_cache shared:MozSSL:10m;  # about 40000 sessions
        ssl_session_tickets off;

        # curl https://ssl-config.mozilla.org/ffdhe2048.txt > /etc/nginx/dhparam
        ssl_dhparam /etc/nginx/dhparam;

        # old configuration
        ssl_protocols TLSv1 TLSv1.1 TLSv1.2 TLSv1.3;
        # @SECLEVEL=0 is required to enable TLS1.0 and 1.1 with modern openssl.
        ssl_ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305:DHE-RSA-AES128-GCM-SHA256:DHE-RSA-AES256-GCM-SHA384:DHE-RSA-CHACHA20-POLY1305:ECDHE-ECDSA-AES128-SHA256:ECDHE-RSA-AES128-SHA256:ECDHE-ECDSA-AES128-SHA:ECDHE-RSA-AES128-SHA:ECDHE-ECDSA-AES256-SHA384:ECDHE-RSA-AES256-SHA384:ECDHE-ECDSA-AES256-SHA:ECDHE-RSA-AES256-SHA:DHE-RSA-AES128-SHA256:DHE-RSA-AES256-SHA256:AES128-GCM-SHA256:AES256-GCM-SHA384:AES128-SHA256:AES256-SHA256:AES128-SHA:AES256-SHA:DES-CBC3-SHA:@SECLEVEL=0;
        ssl_prefer_server_ciphers on;

        # HSTS (ngx_http_headers_module is required) (63072000 seconds)
        add_header Strict-Transport-Security "max-age=63072000" always;

        # OCSP stapling
        ssl_stapling on;
        ssl_stapling_verify on;

        # verify chain of trust of OCSP response using Root CA and Intermediate certs
        ssl_trusted_certificate /etc/nginx/cert/hah.pem;

        # Override response timeouts for threaded_proxy_test path: default is too short if remote client is slow
        location /servercmd/threaded_proxy_test/ {
                proxy_pass http://hah-backend/servercmd/threaded_proxy_test/;
                proxy_set_header Connection "";
                proxy_http_version 1.1;
                proxy_set_header Host \$host;
                proxy_set_header X-Real-IP \$remote_addr;
                send_timeout 1800s;
                proxy_read_timeout 1800s;
        }
        # Actual backend proxy configuration
        location / {
                proxy_pass http://hah-backend;
                proxy_set_header Connection "";
                proxy_http_version 1.1;
                proxy_set_header Host \$host;
                proxy_set_header X-Real-IP \$remote_addr;
        }
        location /redir/ {
                internal;
                alias $HAH_HOME/;
                add_header Cache-Control "public, max-age=31536000";
        }

        # Default root. Should never be used
        root /var/www/html;

        gzip off;
}
END
# In case you're following manually, make sure that the proxy_set* lines have $var not \$var in them and that the alias line isn't a variable.
# normally bash should un-escape/replace them

curl https://ssl-config.mozilla.org/ffdhe2048.txt > /etc/nginx/dhparam

mkdir -p /etc/nginx/cert/private
chmod 0700 /etc/nginx/cert/private

ln -s ../sites-available/hah /etc/nginx/sites-enabled/hah

### WARNING: POTENTIALLY DANGEROUS COMMANDS ###
# remove default nginx site and unneeded modules
rm /etc/nginx/sites-enabled/default
rm /etc/nginx/modules-enabled/*
#####

# Set up the H@H client certificate bridge
cat <<END > /root/hah-cert-handler.sh
#!/bin/bash
HAH_HOME=$(printf %q "$HAH_HOME")
while /bin/true; do
        cat "\$HAH_HOME"/data/keysync.pipe > /dev/null
        if [ -s "\$HAH_HOME"/data/hathcert.p12 ] && [ -r "\$HAH_HOME"/data/hathcert.p12 ]; then
                openssl pkcs12 -info -in "\$HAH_HOME"/data/hathcert.p12 -passin file:/root/hah-pass -nodes -nocerts -legacy > /etc/nginx/cert/private/hah.key \
                || openssl pkcs12 -info -in "\$HAH_HOME"/data/hathcert.p12 -passin file:/root/hah-pass -nodes -nocerts > /etc/nginx/cert/private/hah.key
                openssl pkcs12 -info -in "\$HAH_HOME"/data/hathcert.p12 -passin file:/root/hah-pass -nodes -nokeys -legacy > /etc/nginx/cert/hah.pem \
                || openssl pkcs12 -info -in "\$HAH_HOME"/data/hathcert.p12 -passin file:/root/hah-pass -nodes -nokeys > /etc/nginx/cert/hah.pem
                systemctl reload nginx
        fi
        sleep 5
done
END
chmod 0700 /root/hah-cert-handler.sh

# Make sure your /root/hah-pass file exists and read is restricted
chmod 0600 /root/hah-pass

# run the script now since the crontab entry we will eventually add will not start it until next reboot
/root/hah-cert-handler.sh &

# trigger the cert-handler script
echo sync > "$HAH_HOME"/data/keysync.pipe

#check if the nginx config is ok
nginx -T

docker run -d -v "$HAH_HOME":/home/hah -w /home/hah -p 127.0.0.1:1080:1080 -u $HAH_UID:$HAH_GID --stop-timeout 60 --restart=unless-stopped --log-opt max-size=1m --log-opt max-file=5 --name hah registry.gitlab.com/gruntledw/hentaiathome:latest --port=1080 --disable-ssl --enable-keepalive --trigger-cert-syncfile --file-redirect-header=X-Accel-Redirect --file-redirect-path=/redir/ ; docker logs -f hah

```
Hopefully the H@H client should now start up without problems and the connectivity tests should pass. Hitting Ctrl-C will not stop H@H, just stop following the logs. To actually stop it, run `docker stop hah; docker rm hah`

Add a crontab entry for the cert-handler script, like that it will be automatically restarted when the system is rebooted:
```bash
crontab -e
```
Add the following line at the end, then save and quit
```
@reboot /root/hah-cert-handler.sh &
```

## Migrating from an old setup
Migrating? Set the HAH_HOME environment variable; make sure everything is owned by the correct user and group; and skip the useradd and initial docker run lines. Everything else should work just fine...

## Renaming the docker image
Docker image name a bit to spicy for you?
```bash
docker pull registry.gitlab.com/gruntledw/hentaiathome:latest
docker tag registry.gitlab.com/gruntledw/hentaiathome:latest hah:latest
docker image rm registry.gitlab.com/gruntledw/hentaiathome:latest
```

Then just use `hah:latest` as docker image name instead of `registry.gitlab.com/gruntledw/hentaiathome:latest`

## Alternate network setups
Have more than one IP or network interface? The H@H client has to connect to the RPC servers with the same IP that it will be serving from. If the default one isn't the right one, you'll need some extra configuration. To help facilitate that, you'll likely want to run the docker image on its own network.
```bash
docker network create hah-net
HAH_NET="br-$(docker network ls | grep hah-net | awk '{print $1}')"
```
When you run the docker image, add the `--network hah-net` option.

If you just have a second IP address, you'll have to add an SNAT rule:
```bash
iptables -I POSTROUTING -t nat -i $HAH_NET -j SNAT --to-source correct.src.ip.address
```

If it's a whole other interface, you can use alternate route tables. Use `ip a s` to figure out what the correct values are. "table 10" is just a table with a number selected, it can be any number (as long as it's always the same)
````bash
ip route add default via remote.gateway.ip src local.ip dev iface table 10
ip route add 172.HATH_NET.NETWORK.ADDRESS/NETMASK dev $HATH_NET table 10
ip rule add from 172.HATH_NET.NETWORK.ADDRESS/NETMASK dev $HATH_NET table 10
# You might also want to add this:
ip rule add from local.ip/32 lookup 10
````

How to make these permanent is an exercise for the reader.

## Troubleshooting

Having connection problems? tcpdump is your friend. Useful commands might look like:
```bash
tcpdump -i enp1s0 -n -vvv -s 1500 -X tcp port 443 or tcp port 80 &
tcpdump -i docker0 -n -vvv -s 1500 -X tcp port 1080 &
tail -f /var/log/nginx/access.log /var/log/nginx/error.log &
docker logs -f hah -n 0 &
docker restart hah
```





