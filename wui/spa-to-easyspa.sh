#!/usr/bin/env bash

../gradlew wuiDevBuild

cd static || exit
python3 ../version_static_files.py index.html pace.min.js pace.css main.css exercise.css md.css wui.js
cd ..

cp build/developmentExecutable/wui.js static/js
zip -r -q static.zip static/
rm static/js/wui.js

scp static.zip devgate:
ssh devgate "unzip -q static.zip; rm static.zip; mv static/versioned.index.html /var/www/wui/index.html; rm -rf /var/www/wui/static; mv static /var/www/wui"

rm static.zip
rm static/versioned.index.html
rm static/version.txt


#scp build/developmentExecutable/wui.js devgate:/var/www/wui/static/js/
#scp static/index.html devgate:/var/www/wui/index.html
#scp static/css/main.css devgate:/var/www/wui/static/css/main.css
#scp static/css/md.css devgate:/var/www/wui/static/css/md.css
#scp static/css/exercise.css devgate:/var/www/wui/static/css/exercise.css



# All CSS files
#scp -r static/css/ devgate:/var/www/wui/static

# Pace js file
#scp static/js/pace.min.js devgate:/var/www/wui/static/js/pace.min.js

