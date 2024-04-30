
import java.util.Scanner;

class A {
    int m(int x) {
        if (x < 0) {
            return 0;
        }
        int z = 1;
        while (z < x) {
            z *= 2;
        }
        return z;
    }
}

class B extends A {
    int m(int x) {
        if (x < 0) {
            return 0;
        }
        int z;
        for (z = 1; z < x;) {
            if (x < 10) {
                x *= 2;
            }
            z *= 3;
        }
        return z;
    }
}

class C extends A {
    int m(int x) {
        if (x < 0) {
            return 0;
        }
        int z = 1;
        while (z < x) {
            if (z < 20) {
                z *= 3;
            } else if (z < 30) {
                z *= 4;
            } else {
                z *= 5;
            }
        }
        return z;
    }
}

class Circle {
    double r;

    Circle(double r) {
        this.r = r;
    }

    public double getArea() {
        return (3.14 * r * r);
    }

    public double getCircumference() {
        return (2 * 3.14 * r);
    }
}

class Test {
    static A g;
    int var;
    public static void main(String[] args) {
        Test t = new Test();
        Scanner s = new Scanner(System.in);
        t.var = s.nextInt();
        t.sanityCheckVI();
        t.sanityCheckSR();
    }

    Test() {}

    public void sanityCheckVI() {
        A a1, a2, a3, a4;
        B b1;
        a1 = new A();
        a2 = new B();
        a3 = new C();
        if (var < 0) {
            a4 = new A();
        } else {
            a4 = new B();
        }
        b1 = new B();

        System.out.println(a1.m(var));
        System.out.println(a2.m(var));
        System.out.println(a3.m(var));
        System.out.println(a4.m(var));
        System.out.println(b1.m(var));

        A temp1 = a1;
        A temp3 = a3;
        A temp4 = a4;
        a1 = a2;
        a3 = (A) b1;
        a4 = temp3;
        a2 = temp4;

        System.out.println(a1.m(var));
        System.out.println(a2.m(var));
        System.out.println(a3.m(var));
        System.out.println(a4.m(var));
        System.out.println(b1.m(var));
    }

    public void sanityCheckSR() {
        Circle c1 = new Circle(1);
        Circle c2 = new Circle(2);
        Circle c3 = new Circle(3);
        Circle c4 = new Circle(4);
        if (c3 instanceof Circle) {
            System.out.println(1);
        }
        if (var < 0) {
            c1 = c2;
        }
        if (c1 == c2) {
            System.out.println(2);
        }

        System.out.println(c1.getArea());
        System.out.println(c2.getArea());
        System.out.println(c3.getArea());
        System.out.println(c4.getArea());
    }
}
