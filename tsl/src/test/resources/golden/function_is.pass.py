def faktoriaal(n):
    if n <= 1:
        return 1
    return n * faktoriaal(n - 1)
