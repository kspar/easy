nimi = input()
vanus = input()
print("Tere, " + nimi + "!")
with open("valjund.txt", "w", encoding="utf-8") as f:
    f.write(vanus)
