# coding=utf-8

import docker
import tempfile
import os
import os.path
from time import time, sleep

# Interval in seconds for polling Docker daemon for container status
POLL_INTERVAL_SEC = 0.5

DOCKERFILE_TEMPLATE = '''FROM {}
COPY student-submission /student-submission
COPY evaluate.sh /
CMD /evaluate.sh'''

# Raw docker daemon status strings
RUNNING_STATUS = 'running'
EXITED_STATUS = 'exited'


def run_submission(submission, grading_script, assets, base_image_name, max_run_time_sec, max_mem_MB):
    with tempfile.TemporaryDirectory() as student_dir:
        with open(os.path.join(student_dir, 'Dockerfile'), mode='w', encoding='utf-8') as docker_file:
            docker_file.write(DOCKERFILE_TEMPLATE.format(base_image_name))

        with open(os.path.join(student_dir, 'evaluate.sh'), mode='w', encoding='utf-8') as evaluate_file:
            evaluate_file.write(grading_script)

        os.chmod(os.path.join(student_dir, 'evaluate.sh'), 0o500)

        os.mkdir(os.path.join(student_dir, 'student-submission'))

        with open(os.path.join(student_dir, 'student-submission', 'submission'), mode='w',
                  encoding='utf-8') as submission_file:
            submission_file.write(submission)

        for asset in assets:
            with open(os.path.join(student_dir, 'student-submission', asset[0]), mode='w',
                      encoding='utf-8') as asset_file:
                asset_file.write(asset[1])

        run_in_container(student_dir, max_run_time_sec, max_mem_MB)


def run_in_container(source_dir, max_run_time_sec, max_mem_MB):
    docker_client = docker.from_env()

    # Create image
    image_id = docker_client.images.build(path=source_dir, rm=True)[0].id

    # TODO: remove image after

    # Create and run container
    container = docker_client.containers.run(image_id,
                                             detach=True,
                                             mem_limit='{}m'.format(max_mem_MB))
    start_time = time()

    while True:
        # Reload container status from docker daemon
        container.reload()
        status = container.status

        if status == EXITED_STATUS:
            print('Container finished, removing')
            print(container.logs().decode('utf-8'))
            container.remove()
            break

        elif status == RUNNING_STATUS:
            print('Container still running...')

        else:
            print('Unexpected container status: ' + status)

        if time() - start_time > max_run_time_sec:
            print('Timeout, killing container')
            container.kill()
            container.remove()
            break

        sleep(POLL_INTERVAL_SEC)
