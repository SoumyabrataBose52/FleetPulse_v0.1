FROM nginx:alpine

COPY services/web/nginx.conf /etc/nginx/conf.d/default.conf
COPY services/web/index.html /usr/share/nginx/html/index.html
COPY services/web/css /usr/share/nginx/html/css
COPY services/web/js /usr/share/nginx/html/js

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
