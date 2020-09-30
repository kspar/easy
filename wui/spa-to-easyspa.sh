#!/usr/bin/env bash

# Add page generation time to html
d=$(date)
s="s#<div id=\"date\">.*</div>#<div id=\"date\">$d</div>#g"
sed -i "$s" static/spaproto.html


# Only html
scp static/spaproto.html devgate:/var/www/wui/index.html

# Only main CSS file
#scp static/css/main.css devgate:/var/www/wui/static/css/main.css

# All CSS files
scp -r static/css/ devgate:/var/www/wui/static

# wui js file
scp build/distributions/wui.js devgate:/var/www/wui/static/js/

# wui map
scp build/distributions/wui.js.map devgate:/var/www/wui/static/js/

# Pace js file
#scp static/js/pace.min.js devgate:/var/www/wui/static/js/pace.min.js


# Remove page generation time
b="s+<div id=\"date\">.*</div>+<div id=\"date\"></div>+g"
sed -i "$b" static/spaproto.html
