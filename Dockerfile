FROM maven:3.8.2-jdk-8-slim
ENV DEBIAN_FRONTEND=noninteractive
RUN apt update
RUN apt install -y python3 python3-pip
RUN python3 -m pip install awscli