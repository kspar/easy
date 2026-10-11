import os
import shutil
import ast
from PIL import Image
import io


def getter(widget):
    widget.update()
    ps = widget.postscript(colormode='color')
    img = Image.open(io.BytesIO(ps.encode('utf-8')))
    img.save("screenshot.jpg")

def get_student_file():
    return 'submission.py'


if __name__ == '__main__':
    student_file = get_student_file()
    with open(student_file) as f:
        orig_content = f.read()
    
    shutil.copy(student_file, 'orig_student_submission.py')
    
    canvas_name = None
    tk_name = None
    tree = ast.parse(orig_content)
    for node in ast.walk(tree):
        if (isinstance(node, ast.Assign) and 
            isinstance(node.value, ast.Call) and
            hasattr(node.value, 'func') and 
                ((isinstance(node.value.func, ast.Name) and
                    hasattr(node.value.func, 'id') and node.value.func.id == 'Tk')
                or
                (isinstance(node.value.func, ast.Attribute) and
                    hasattr(node.value.func, 'attr') and node.value.func.attr == 'Tk'))
            ):
                tk_name = node.targets[0].id
        if (isinstance(node, ast.Assign) and 
            isinstance(node.value, ast.Call) and
            hasattr(node.value, 'func') and 
                ((isinstance(node.value.func, ast.Name) and
                    hasattr(node.value.func, 'id') and node.value.func.id == 'Canvas')
                or
                (isinstance(node.value.func, ast.Attribute) and
                    hasattr(node.value.func, 'attr') and node.value.func.attr == 'Canvas'))
            ):
                canvas_name = node.targets[0].id
    
    if tk_name is None:
        raise RuntimeError('Tk name not found')
    if canvas_name is None:
        raise RuntimeError('Canvas name not found')
    
    lines = orig_content.split('\n')
    for i in range (0, len(lines)):
        if "mainloop()" in lines[i]:
            spaces = len(lines[i]) - len(lines[i].lstrip())
            lines[i] = "#mainloop()"
            lines[i-1] += ("\n" + " " * spaces + "from kontroll import getter\n" + 
                " " * spaces + "getter({})\n".format(canvas_name))
    
    with open("modified_student_submission.py", "w") as f:
        f.write("\n".join(lines))
