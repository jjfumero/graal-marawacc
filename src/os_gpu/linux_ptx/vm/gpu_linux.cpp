/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#include "runtime/gpu.hpp"
#include "utilities/ostream.hpp"

void gpu::probe_gpu() {
  set_available(gpu::Linux::probe_gpu());
  if (TraceGPUInteraction) {
    tty->print_cr("gpu_linux::probe_gpu(): %d", gpu::is_available());
  }
}

/*
 * Probe for CUDA device on PCI bus using /proc/bus/pci/devices. Do
 * not rely on CUDA tool kit being installed. We will check if CUDA
 * library is installed later.
 */

static unsigned int nvidia_vendor_id = 0x10de;
static unsigned int nvidia_gk110_dev_id = 0x1005;

bool gpu::Linux::probe_gpu() {
  /* 
   * Open /proc/bus/pci/devices to look for the first CUDA enabled
   * device. For now, finding the first CUDA device. Will need to
   * revisit this to support execution on multiple CUDA devices if
   * they exist.
   */
  FILE *pci_devices = fopen("/proc/bus/pci/devices", "r");
  char contents[4096];
  unsigned int bus_num_devfn_ign;
  unsigned int vendor;
  unsigned int device;
  bool cuda_device_exists = false;
  if (pci_devices == NULL) {
    tty->print_cr("*** Failed to open /proc/bus/pci/devices");
    return cuda_device_exists;
  }

  while (fgets(contents, sizeof(contents)-1, pci_devices)) {
    sscanf(contents, "%04x%04x%04x", &bus_num_devfn_ign, &vendor, &device);
    /* Break after finding the first CUDA device. */
    if ((vendor == nvidia_vendor_id) && (device = nvidia_gk110_dev_id)) {
      cuda_device_exists = true;
      if (TraceGPUInteraction) {
        tty->print_cr("Found supported nVidia CUDA device vendor : 0x%04x device 0x%04x", vendor, device);
      }
      break;
    }
  }

  // Close file pointer.
  fclose(pci_devices);

  return cuda_device_exists;
}
