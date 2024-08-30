import sys
import time


def create_versioned_files(src_filename, filenames):
    timestamp = str(int(time.time()))

    with open(src_filename, encoding='utf-8') as html_file:

        html_file_content = html_file.read()

        for filename in filenames:
            usages_count = html_file_content.count(filename)
            if usages_count != 1:
                print('ERROR: Found {} usages for file {} (expected exactly 1)'.format(usages_count, filename))
                return

            new_filename = "{}?v={}".format(filename, timestamp)
            html_file_content = html_file_content.replace(filename, new_filename)

        html_file_content = html_file_content.replace('ez_wui_version_placeholder', timestamp)

    with open('versioned.' + src_filename, mode="w", encoding="utf-8") as f:
        f.write(html_file_content)

    with open('version.txt', mode='w') as f:
        f.write(timestamp)


if __name__ == '__main__':
    create_versioned_files(sys.argv[1], sys.argv[2:])
