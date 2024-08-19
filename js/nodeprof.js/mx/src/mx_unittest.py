#
# ----------------------------------------------------------------------------------------------------
#
# Copyright (c) 2023, 2023, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.
#
# ----------------------------------------------------------------------------------------------------
"""
Proxy file for the mx_unittest module.

Exposes public symbols from the original module and possibly some private ones.

DO NOT WRITE IMPLEMENTATION CODE HERE.

See docs/package-structure.md for more details.
"""

# pylint: disable=wildcard-import,unused-wildcard-import
from mx._impl.mx_unittest import *

# pylint: disable=unused-import
from mx._impl.mx_unittest import Action, _run_tests, _VMLauncher, _config_participants
from mx._legacy.oldnames import redirect as _redirect

from mx._impl.mx_unittest import __all__ as _unittest_symbols

# TODO: [GR-48911] Users should rather use argparse.Action
__legacy__ = ["Action"]

__all__ = _unittest_symbols + __legacy__

_redirect(
    __name__,
    allowed_internal_reads=[
        "_run_tests",
        "_VMLauncher",
        "_config_participants",
    ],
)
