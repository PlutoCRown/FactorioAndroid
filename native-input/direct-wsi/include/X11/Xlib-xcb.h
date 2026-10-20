#pragma once
#include <X11/Xlib.h>
#include <xcb/xcb.h>
xcb_connection_t *XGetXCBConnection(Display *);
