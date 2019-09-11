# coding=utf-8

import enum
import os
import os.path
import tempfile
import uuid
from time import time, sleep

import docker
import docker.errors

# Interval in seconds for polling Docker daemon for container status
POLL_INTERVAL_SEC = 0.5

DOCKERFILE_TEMPLATE = '''FROM {}
COPY student-submission /student-submission
COPY evaluate.sh /
COPY {} /
CMD /evaluate.sh'''

# Raw docker daemon status strings
RUNNING_STATUS = 'running'
EXITED_STATUS = 'exited'


def grade_submission(submission, grading_script, assets, base_image_name, max_run_time_sec, max_mem_MB, logger,
                     request_id):
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

    uu = str(uuid.uuid4())

    # Create temporary dir for this submission and write submission data as files
    with tempfile.TemporaryDirectory() as student_dir:
        with open(os.path.join(student_dir, 'Dockerfile'), mode='w', encoding='utf-8') as docker_file:
            docker_file.write(DOCKERFILE_TEMPLATE.format(base_image_name, uu))

        with open(os.path.join(student_dir, 'evaluate.sh'), mode='w', encoding='utf-8') as evaluate_file:
            evaluate_file.write(grading_script)

        # Grading script needs read and execution permissions
        os.chmod(os.path.join(student_dir, 'evaluate.sh'), 0o500)

        os.mkdir(os.path.join(student_dir, 'student-submission'))

        with open(os.path.join(student_dir, uu), mode='w', encoding='utf-8') as _:
            pass

        with open(os.path.join(student_dir, 'student-submission', 'submission'), mode='w',
                  encoding='utf-8') as submission_file:
            submission_file.write(submission)

        for asset in assets:
            with open(os.path.join(student_dir, 'student-submission', asset[0]), mode='w',
                      encoding='utf-8') as asset_file:
                asset_file.write(asset[1])

        return _run_in_container(student_dir, max_run_time_sec, max_mem_MB, logger, request_id)


def _run_in_container(source_dir, max_run_time_sec, max_mem_MB, logger, request_id):
    docker_client = docker.from_env()

    # Create image
    image_id = docker_client.images.build(path=source_dir, rm=True)[0].id
    logger.debug('Built image {} ({})'.format(image_id, request_id))

    # Create and run container
    container = docker_client.containers.run(image_id, detach=True, mem_limit='{}m'.format(max_mem_MB))
    logger.debug("Started container {} ({})".format(container.short_id, request_id))
    start_time = time()
    i = 0

    while True:
        # Reload container status from docker daemon
        container.reload()
        status = container.status
        if status == EXITED_STATUS:
            logger.info('Container exited ({})'.format(request_id))
            run_status = RunStatus.SUCCESS
            break
        elif status == RUNNING_STATUS:
            logger.debug('Container still running... iteration {} ({})'.format(i, request_id))
        else:
            logger.error('Unexpected container status {} ({})'.format(status, request_id))

        if time() - start_time > max_run_time_sec:
            logger.warn('Timeout, killing container ({})'.format(request_id))
            try:
                container.kill()
            except docker.errors.APIError as e:
                logger.error("{} ({})".format(e, request_id))
            run_status = RunStatus.TIME_EXCEEDED
            break

        i += 1
        sleep(POLL_INTERVAL_SEC)

    output = container.logs().decode('utf-8')
    logger.debug('Removing container {} ({})'.format(container.short_id, request_id))
    container.remove()
    logger.debug('Removing image {} ({})'.format(image_id, request_id))
    try:
        docker_client.images.remove(image=image_id)
    except docker.errors.APIError as e:
        logger.error('{}, ({})'.format(e, request_id))

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
