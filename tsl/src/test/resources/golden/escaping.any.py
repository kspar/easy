# escaping.json is not a gradeable exercise — its expected values are the hostile strings
# themselves. This submission exists so that script is still handed to tiivad, which is the point:
# it is the nastiest thing the compiler emits, and the contract check wants it. Whether it scores
# anything is deliberately not asserted; see cases() in test_tiivad_contract.py.
x = "the student's answer"
print(x)
