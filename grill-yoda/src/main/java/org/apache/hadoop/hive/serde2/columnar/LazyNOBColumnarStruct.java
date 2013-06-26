package org.apache.hadoop.hive.serde2.columnar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.serde2.lazy.ByteArrayRef;
import org.apache.hadoop.hive.serde2.lazy.LazyObjectBase;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.inmobi.dw.yoda.proto.NetworkObject.KeyLessNetworkObject;
import com.inmobi.dw.yoda.tools.util.cube.CubeDefinitionReader;

public class LazyNOBColumnarStruct extends ColumnarStructBase {
  private static final Log LOG = LogFactory.getLog(LazyNOBColumnarStruct.class);

  KeyLessNetworkObject.Builder nobBuilder = KeyLessNetworkObject.newBuilder();
  static final Set<String> measures = new HashSet<String>();
  static {
    CubeDefinitionReader reader = CubeDefinitionReader.get();
    for (String cubeName : reader.getCubeNames()) {
      measures.addAll(reader.getAllMeasureNames(cubeName));
      for (String summary : reader.getSummaryNames(cubeName)) {
        measures.addAll(reader.getSummaryMeasureNames(cubeName, summary));
      }
    }
  }

  class NOBFieldInfo {
    Object value = null;
    boolean retrievedData = false;
    FieldDescriptor fd;
    KeyLessNetworkObject nob;

    protected Object getField() {
      if (!retrievedData) {
        if (nob.hasField(fd)) {
          Object nobField = nob.getField(fd);
          value = nobField;
          if (nobField instanceof String) {
            if (measures.contains(fd.getName())) {
              try {
                double val = Double.parseDouble((String) nobField);
                value = val;
              } catch (NumberFormatException e) {
                LOG.warn("Error parsing " + fd.getName() 
                    + ", value " + nob.getField(fd), e);
              }
            }
          } 
        } else {
          value = null;
        }
        retrievedData = true;
      }
      return value;
    }

    public void init(KeyLessNetworkObject nob,
        FieldDescriptor fieldDescriptor) {
      this.nob = nob;
      this.fd = fieldDescriptor;
      retrievedData  = false;
    }
  }

  private NOBFieldInfo[] fieldInfoList = null;
  private ArrayList<Object> cachedList;

  private int numCols;
  private int[] referedCols;
  public LazyNOBColumnarStruct(ObjectInspector oi, int numCols, int[] referedCols) {
    super(oi, new ArrayList<Integer>());
    this.numCols = numCols;
    fieldInfoList = new NOBFieldInfo[numCols];
    for (int i = 0; i < numCols; i++) {
      fieldInfoList[i] = new NOBFieldInfo();
    }
    this.referedCols = referedCols;
  }

  /**
   * Get one field out of the struct.
   *
   * @param fieldID
   *          The field ID
   * @return The field as a LazyObject
   */
  public Object getField(int fieldID) {
    Object o = fieldInfoList[fieldID].getField();
    return o;
  }


  /**
   * Get the values of the fields as an ArrayList.
   *
   * @return The values of the fields as an ArrayList.
   */
  public ArrayList<Object> getFieldsAsList() {
    if (cachedList == null) {
      cachedList = new ArrayList<Object>();
    } else {
      cachedList.clear();
    }
    for (int i = 0; i < fieldInfoList.length; i++) {
      cachedList.add(fieldInfoList[i].getField());
    }
    return cachedList;
  }

  public void init(BytesRefArrayWritable cols) {
    int numGroups = LazyNOBColumnarSerde.fieldGroups.size();
    nobBuilder.clear();
    for (int i : referedCols) {
      BytesRefWritable bytesRef = cols.get(i);
      if (bytesRef.getLength() != 0) {
        try {
          nobBuilder.mergeFrom(bytesRef.getData(),
              bytesRef.getStart(), bytesRef.getLength());
        } catch (Exception e) {
          throw new RuntimeException("Could not initialize network object", e);
        }
      }
    }
    KeyLessNetworkObject nob = nobBuilder.build();

    List<FieldDescriptor> fdList = new ArrayList<FieldDescriptor>();
    fdList.addAll(nob.getDescriptorForType().getFields());
    for (int i = 0; i < numCols; i++) {
      fieldInfoList[i].init(nob, fdList.get(i));
    }
  }

  @Override
  protected LazyObjectBase createLazyObjectBase(ObjectInspector arg0) {
    return null;
  }

  @Override
  protected int getLength(ObjectInspector arg0, ByteArrayRef arg1, int arg2,
      int arg3) {
    return 0;
  }
}