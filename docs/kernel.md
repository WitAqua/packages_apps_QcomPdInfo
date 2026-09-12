# What the kernel has to publish

This app does not talk to the port. It reads what the kernel already knows, so
what it can show depends entirely on which interface the kernel exposes. There
are two, and which one a board has is a kernel-version question rather than a
configuration one.

## The two interfaces

### Qualcomm's own driver

`drivers/usb/pd/policy_engine.c`, which has carried the same interface from
msm8998 through the sm8xxx parts on 4.x and 5.4:

```
/sys/class/usbpd/usbpd0/pdo1 .. pdo7   the source capabilities, as 32-bit words
/sys/class/usbpd/usbpd0/rdo            the request, likewise
/sys/class/usbpd/usbpd0/contract       "explicit" once negotiated
/sys/class/usbpd/usbpd0/current_pr     "sink" / "source" / "none"
/sys/class/usbpd/usbpd0/current_dr     "ufp" / "dfp" / "none"
```

This is the better of the two to read: it is the only one that publishes the
request object, so the negotiated voltage and current both come out of it. No
kernel work is needed on a board that has it.

### The upstream class

`drivers/usb/typec/pd.c`, from Linux 5.18. Each object is a directory of
decimal fields rather than one word:

```
/sys/class/usb_power_delivery/pd0/revision
/sys/class/usb_power_delivery/pd0/source-capabilities/<position>:<type>/...
/sys/class/typec/port0-partner/usb_power_delivery   -> the charger's pd device
```

The types are `fixed_supply`, `battery`, `variable_supply`,
`programmable_supply` and `spr_adjustable_voltage_supply`, and the fields are
documented in `Documentation/ABI/testing/sysfs-class-usb_power_delivery`.

It has no request object anywhere in the class. What was actually taken has to
come from elsewhere - see below.

## The problem this app was written around

The upstream class only carries the object lists when the driver read them, and
with UCSI that means the firmware reporting that it can list them:

```c
static int ucsi_get_pdos(struct ucsi_connector *con, enum typec_role role,
			 int is_partner, u32 *pdos)
{
	struct ucsi *ucsi = con->ucsi;
	...
	if (!(ucsi->cap.features & UCSI_CAP_PDO_DETAILS))
		return 0;
```

and `ucsi_get_pd_caps()` registers nothing when that returns zero. The
`usb_power_delivery` devices are created regardless, so a platform whose
firmware does not set the bit ends up with `pd0` and `pd1` present and empty -
which looks like a bug and is not one.

Checked on a handset: SM8850 (`ro.board.platform=canoe`), kernel
6.12.23-android16, `ucsi_glink` over `pmic-glink`. `GET_CAPABILITY` returns a
features field of **zero** - no PDO details, no alternate mode details, nothing
- while `GET_PDOS` answers perfectly well when put to it directly. The firmware
simply does not fill the field in.

Mainline carries quirks in the other direction for the same family
(`UCSI_NO_PARTNER_PDOS` in `ucsi_glink.c`), so this is a known area.

## Getting the lists on such a platform

### Without touching the kernel

`drivers/usb/typec/ucsi/debugfs.c` exposes the policy manager directly:

```
/sys/kernel/debug/usb/ucsi/<name>/command    a command word, written
/sys/kernel/debug/usb/ucsi/<name>/response   its response, read back
```

`ucsi_cmd()` accepts `UCSI_GET_PDOS` and **does not consult**
`UCSI_CAP_PDO_DETAILS`, so the question can be put even where the driver will
not put it. `UCSI_GET_CONNECTOR_STATUS` carries the request object, which is
where the driver gets it from too, so both halves are recoverable.

The app uses both: the object lists only where the class has none, and the
connector status whenever it can, because the class never carries the request.
This is what the module exists to enable - debugfs is not mounted by default,
and for good reason. See
[Qcom-PD-Info-Module](https://github.com/WitAqua-tools/Qcom-PD-Info-Module).

It needs root, and an SELinux context that may reach debugfs - the shell's may
not. It is not something a ROM should rely on.

### The one-line fix

For a ROM that builds its own kernel, dropping the gate is the whole change:

```diff
 static int ucsi_get_pdos(struct ucsi_connector *con, enum typec_role role,
 			 int is_partner, u32 *pdos)
 {
 	struct ucsi *ucsi = con->ucsi;
 	u8 num_pdos;
 	int ret;
 
-	if (!(ucsi->cap.features & UCSI_CAP_PDO_DETAILS))
-		return 0;
-
 	/* UCSI max payload means only getting at most 4 PDOs at a time */
 	ret = ucsi_read_pdos(con, role, is_partner, pdos, 0, UCSI_MAX_PDOS);
```

`ucsi_read_pdos()` already handles the command failing, so a firmware that
genuinely cannot answer costs one refused command rather than a broken port.

With this in place the object lists populate normally and every other reader of
the class benefits too. It also fixes UCSI's own power supply: `voltage_now`
reads `src_pdos[index - 1]`, which the same check was leaving empty, so it had
been reporting zero.

A quirk flag - the inverse of `UCSI_NO_PARTNER_PDOS` - is the shape this should
take upstream rather than an unconditional removal.

**It does not get you the request object.** The class has no attribute for it at
any kernel version, and nothing else exposes `con->rdo` either; the only other
place it appears is `GET_CONNECTOR_STATUS`. So a patched kernel publishes the
whole menu and still cannot say which line was ordered, which is why the app
asks over debugfs whenever it can rather than only when the lists are missing.

That makes the request a root-only figure on the upstream path. A board with
qualcomm's own driver has it in sysfs and needs none of this.

### If the firmware really cannot answer GET_PDOS

UCSI 2.0 added `GET_PD_MESSAGE`, which can fetch the raw `Source_Capabilities`
message. `ucsi.c` already has `ucsi_get_pd_message()` for Discover Identity, so
the plumbing exists; requesting the capabilities message and registering what
comes back would be the spec-sanctioned route. Not needed on any platform
checked so far.

## Where this should end up

Two kernel changes would between them remove the need for debugfs entirely, and
neither is large. Noted here as the intended direction rather than as work
done.

**Drop the capability gate.** The diff above. Gets the object lists onto every
reader of the class, and fixes UCSI's own `voltage_now` while it is there.

**Publish the request object.** `con->rdo` is already in the driver, and
`ucsi_psy.c` reads it - what is missing is anywhere to see it from userspace.
An attribute on the connector, or better a `request` on the
`usb_power_delivery` device beside the capabilities it was made against, would
close the one gap that no amount of policy can. It is a real omission rather
than a quirk: `drivers/usb/typec/pd.c` has nothing for a request at any kernel
version, so TCPM ports lack it for the same reason UCSI ones do. Worth putting
upstream.

With both, the object lists and the request come out of sysfs, the app needs
neither root nor debugfs on the upstream path, and the module has nothing left
to mount.

Not urgent for the boards WitAqua builds kernels for, which have qualcomm's own
driver and therefore both already. The gain is on the newer parts, where the
kernel is somebody else's.

## What cannot be recovered

Nothing in the upstream class publishes the request object, and
`GET_CONNECTOR_STATUS` is the only place it appears. Where neither is reachable,
the negotiated current can still be had from UCSI's own power supply:

```
/sys/class/power_supply/ucsi-source-psy-*/current_now
```

but read that with care. It is `rdo_op_current(con->rdo)`, which takes the
fixed-supply field whatever the request actually is - against a programmable
supply that lands on the wrong bits and gives a figure that means nothing. The
app shows it only where the request object itself is out of reach.
