#!/usr/bin/env bash

# Add page generation time to html
#d=$(date)
#s="s#<div id=\"date\">.*</div>#<div id=\"date\">$d</div>#g"
#sed -i "$s" static/spaproto.html


#scp static/spaproto.html devgate:/var/www/wui/index.html
scp static/css/main.css devgate:/var/www/wui/static/css/main.css
scp build/distributions/wui.js devgate:/var/www/wui/static/js/



# All CSS files
#scp -r static/css/ devgate:/var/www/wui/static

# Pace js file
#scp static/js/pace.min.js devgate:/var/www/wui/static/js/pace.min.js


# Remove page generation time
#b="s+<div id=\"date\">.*</div>+<div id=\"date\"></div>+g"
#sed -i "$b" static/spaproto.html
