# Copyright (c) 2019, NVIDIA CORPORATION.

import cudf._libxx as libcudfxx
from cudf.utils import ioutils


@ioutils.doc_read_avro()
def read_avro(
    filepath_or_buffer,
    engine="cudf",
    columns=None,
    skip_rows=None,
    num_rows=None,
    **kwargs,
):
    """{docstring}"""

    from cudf import DataFrame

    filepath_or_buffer, compression = ioutils.get_filepath_or_buffer(
        filepath_or_buffer, None, **kwargs
    )
    if compression is not None:
        ValueError("URL content-encoding decompression is not supported")

    if engine == "cudf":
        return DataFrame._from_table(
            libcudfxx.avro.read_avro(
                filepath_or_buffer, columns, skip_rows, num_rows
            )
        )
    else:
        raise NotImplementedError("read_avro currently only supports cudf")
