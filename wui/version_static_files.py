import sys
import time


HTML_FILE_NAME = 'index.html'
NEW_HTML_FILE_NAME = 'versioned.index.html'


def create_versioned_files(filenames):
    timestamp = int(time.time())

    with open(HTML_FILE_NAME, encoding='utf-8') as html_file:

        html_file_content = html_file.read()

        for filename in filenames:
            usages_count = html_file_content.count(filename)
            if usages_count != 1:
                print('ERROR: Found {} usages for file {} (expected exactly 1)'.format(usages_count, filename))
                return

            new_filename = "{}?v={}".format(filename, timestamp)
            html_file_content = html_file_content.replace(filename, new_filename)

    with open(NEW_HTML_FILE_NAME, mode="w", encoding="utf-8") as f:
        f.write(html_file_content)


if __name__ == '__main__':
    create_versioned_files(sys.argv[1:])
