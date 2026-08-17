import socket
import sys


port_count = int(sys.argv[1])
sockets = []
try:
    for _ in range(port_count):
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        sockets.append(listener)

    print(" ".join(str(listener.getsockname()[1]) for listener in sockets))
finally:
    for listener in sockets:
        listener.close()
