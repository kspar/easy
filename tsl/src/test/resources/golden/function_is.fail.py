def faktoriaal(n):
    tulemus = 1
    for i in range(2, n + 1):
        tulemus *= i
    return tulemus
