#!/usr/bin/env bash


scp build/developmentExecutable/wui.js devgate:/var/www/wui/static/js/
scp static/spaproto.html devgate:/var/www/wui/index.html
scp static/css/main.css devgate:/var/www/wui/static/css/main.css



# All CSS files
#scp -r static/css/ devgate:/var/www/wui/static

# Pace js file
#scp static/js/pace.min.js devgate:/var/www/wui/static/js/pace.min.js

