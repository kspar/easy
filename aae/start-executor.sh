#!/usr/bin/env bash

# TODO: privilege escalation should be a privilege not be taken for granted
sudo gunicorn3 -c gunicorn-conf.py server:app
