# coding=utf-8

import docker
import tempfile
import os
import os.path
import enum
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


def grade_submission(submission, grading_script, assets, base_image_name, max_run_time_sec, max_mem_MB, logger):
    """
    :param submission: str, submission content
    :param grading_script: str, grading script content
    :param assets: list[tuple[str, str]], list on pairs (file_name, file_content), one pair for each asset
    :param base_image_name: str, name of base docker image that contains dependencies for the grading script,
                                note that this image must already exist
    :param max_run_time_sec: int, maximum run time of the container / grading script in seconds
    :param max_mem_MB: int, maximum memory usage of the container in megabytes, must be >= 4
    :param logger: logger object, must have standard debug, info etc methods

    :return pair (run_status: RunStatus, raw_output: str)
    """

    # Create temporary dir for this submission and write submission data as files
    with tempfile.TemporaryDirectory() as student_dir:
        with open(os.path.join(student_dir, 'Dockerfile'), mode='w', encoding='utf-8') as docker_file:
            docker_file.write(DOCKERFILE_TEMPLATE.format(base_image_name))

        with open(os.path.join(student_dir, 'evaluate.sh'), mode='w', encoding='utf-8') as evaluate_file:
            evaluate_file.write(grading_script)

        # Grading script needs read and execution permissions
        os.chmod(os.path.join(student_dir, 'evaluate.sh'), 0o500)

        os.mkdir(os.path.join(student_dir, 'student-submission'))

        with open(os.path.join(student_dir, 'student-submission', 'submission'), mode='w',
                  encoding='utf-8') as submission_file:
            submission_file.write(submission)

        for asset in assets:
            with open(os.path.join(student_dir, 'student-submission', asset[0]), mode='w',
                      encoding='utf-8') as asset_file:
                asset_file.write(asset[1])

        return _run_in_container(student_dir, max_run_time_sec, max_mem_MB, logger)


def _run_in_container(source_dir, max_run_time_sec, max_mem_MB, logger):
    docker_client = docker.from_env()

    # Create image
    image_id = docker_client.images.build(path=source_dir, rm=True)[0].id

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
            logger.info('Container exited')
            run_status = RunStatus.SUCCESS
            break
        elif status == RUNNING_STATUS:
            logger.debug('Container still running...')
        else:
            logger.error('Unexpected container status: ' + status)

        if time() - start_time > max_run_time_sec:
            logger.warn('Timeout, killing container')
            container.kill()
            run_status = RunStatus.TIME_EXCEEDED
            break

        sleep(POLL_INTERVAL_SEC)

    output = container.logs().decode('utf-8')
    container.remove()
    docker_client.images.remove(image=image_id)

    if _was_memory_killed(output):
        run_status = RunStatus.MEM_EXCEEDED

    return run_status, output


def _was_memory_killed(output):
    # Assume the process was killed by OOM killer if the last non-empty lowercased line of the output contains 'killed'
    return 'killed' in output.strip().split('\n')[-1].lower()


@enum.unique
class RunStatus(enum.Enum):
    SUCCESS = enum.auto()
    TIME_EXCEEDED = enum.auto()
    MEM_EXCEEDED = enum.auto()
