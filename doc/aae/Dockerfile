FROM python:3

ENV PYTHONIOENCODING utf-8

COPY python-grader /python-grader
COPY student-submission /student-submission
COPY evaluate.sh /

RUN cd /python-grader \
    && python3 /python-grader/setup.py install \
    && cd /

CMD ./evaluate.sh
