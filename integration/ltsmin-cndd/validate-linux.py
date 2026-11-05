#!/usr/bin/env python3
"""Compatibility entry point for the current upstream validation driver."""
from pathlib import Path
import runpy
runpy.run_path(str(Path(__file__).with_name('validate.py')), run_name='__main__')
