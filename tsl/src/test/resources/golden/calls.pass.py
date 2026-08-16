# calls.json: Koer.peaosa must call a Loom class function.
class Loom:
    def liigu(self):
        return "liigub"

class Koer(Loom):
    def peaosa(self):
        return Loom.liigu(self)
