#!/usr/bin/env python3
"""Validate demo loopback ports; bind probes release sockets without a server."""
import os,socket,sys

def ports(env):
    values=[]
    for key,default in [('LEDGER_PORT','41000'),('LEDGER_HTTP_PORT','41001')]:
        raw=env.get(key,default)
        if not raw.isascii() or not raw.isdecimal() or not 1024<=int(raw)<=65535:
            raise ValueError(f'{key} must be an integer from 1024 to 65535')
        values.append(int(raw))
    if values[0]==values[1]:raise ValueError('LEDGER_HTTP_PORT must differ from LEDGER_PORT')
    return values

def check(env,bind=True):
    values=ports(env)
    if bind:
        sockets=[]
        try:
            for key,port in zip(('LEDGER_PORT','LEDGER_HTTP_PORT'),values):
                s=socket.socket();sockets.append(s)
                try:s.bind(('127.0.0.1',port))
                except OSError:raise ValueError(f'host port {port} unavailable; override {key}')
        finally:
            for s in sockets:s.close()
if __name__=='__main__':
    try:check(os.environ,'--validate-only' not in sys.argv)
    except ValueError as error:print('BLOCKED: '+str(error),file=sys.stderr);sys.exit(2)
