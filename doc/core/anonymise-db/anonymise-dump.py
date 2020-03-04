from random import sample
import re

PSEUDO_PAIRS = 3190


def read_wordlist(filename):
    with open(filename) as f:
        return list(set(filter(lambda l: l != '', map(lambda l: l.strip().lower(), f.readlines()))))


def create_word_pairs(filename1, filename2):
    raw_words_1 = read_wordlist(filename1)
    raw_words_2 = read_wordlist(filename2)
    possible_combinations = len(raw_words_1) * len(raw_words_2)
    if PSEUDO_PAIRS > possible_combinations:
        raise Exception("Unique pseudonym pairs not possible. Required: {}, possible: {}".format(PSEUDO_PAIRS,
                                                                                                 possible_combinations))

    pairs = []
    for w1 in raw_words_1:
        for w2 in raw_words_2:
            pairs.append((w1, w2))

    return sample(pairs, PSEUDO_PAIRS)


def create_next_email(pair):
    return "{}.{}@ez.ez".format(pair[0], pair[1]).replace('õ', 'o').replace('ä', 'a').replace('ö', 'o')\
        .replace('ü', 'u').replace('š', 's').replace('ž', 'z')


def read_dump(filename):
    with open(filename) as f:
        return f.readlines()



if __name__ == '__main__':

    pseudo_pairs = create_word_pairs('varvid.txt', 'linnud.txt')
    dump_lines = read_dump("dump.sql")

    account_section_start = dump_lines.index(
        'COPY public.account (username, created_at, email, given_name, family_name, moodle_username) FROM stdin;\n')
    i = 1
    while True:
        pseudo_pair = pseudo_pairs.pop()
        pseudo_given_name = pseudo_pair[0].capitalize()
        pseudo_family_name = pseudo_pair[1].capitalize()
        pseudo_email = create_next_email(pseudo_pair)

        old_account_row = dump_lines[account_section_start + i]
        if old_account_row == '\\.\n':
            break

        new_account_row = re.sub(r'(.+?\t.+?\t).+?\t.+?\t.+?(\t.*)',
                                 r'\1{}\t{}\t{}\2'.format(pseudo_email, pseudo_given_name, pseudo_family_name),
                                 old_account_row)
        dump_lines[account_section_start + i] = new_account_row

        i += 1

    with open("dump-anon.sql", 'w', encoding='utf-8') as f:
        f.writelines(dump_lines)

