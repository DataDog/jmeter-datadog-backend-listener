FROM ubuntu:latest
RUN apt update
RUN apt install -y openjdk-8 maven python3 python3-pip
RUN python3 -m pip install awscli