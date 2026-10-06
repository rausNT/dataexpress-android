from pathlib import Path
import struct,sys
import os
work=Path(os.environ.get('DX_ARM32_WORK','/root/dataexpress-arm32'))
artifact=Path(__file__).resolve().parent
relative=Path('usr/lib/i386-linux-gnu/wine/i386-windows')
def sections(b):
    pe=struct.unpack_from('<I',b,60)[0]
    assert b[pe:pe+4]==b'PE\0\0'
    count=struct.unpack_from('<H',b,pe+6)[0]
    size=struct.unpack_from('<H',b,pe+20)[0]
    return {bytes(b[o:o+8]).rstrip(b'\0'):(o+36,struct.unpack_from('<I',b,o+36)[0])
            for o in range(pe+24+size,pe+24+size+40*count,40)}
patches=[]
for original in (work/'wine-original'/relative).iterdir():
    target=work/'rootfs'/relative/original.name
    if not target.is_file() or target.is_symlink() or original.suffix not in ['.dll','.exe']: continue
    source=sections(original.read_bytes()); data=bytearray(target.read_bytes())
    for name,(offset,flags) in sections(data).items():
        if name in source and source[name][1]!=flags:
            wanted=source[name][1]
            patches.append(f'{original.name} {offset} {flags:08x} {wanted:08x}\n')
            struct.pack_into('<I',data,offset,wanted)
    target.write_bytes(data)
(artifact/'pe-flags.tsv').write_text(''.join(patches))
print('Restored',len(patches),'section flags')
