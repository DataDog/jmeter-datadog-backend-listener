FROM registry.ddbuild.io/images/mirror/maven:3.8.6-openjdk-8@sha256:ff18d86faefa15d1445d0fa4874408cc96dec068eb3487a0fc6d07f359a24607
ENV DEBIAN_FRONTEND=noninteractive
RUN apt update
RUN apt install -y python3 python3-pip
RUN python3 -m pip install awscli