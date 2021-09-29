FROM ubuntu:latest
ENV DEBIAN_FRONTEND=noninteractive
RUN apt update
RUN apt install -y openjdk-8-jdk maven python3 python3-pip
RUN python3 -m pip install awscli